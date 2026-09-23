package wagateway

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"sync"
	"unicode"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
	"time"
)

// Client wraps whatsmeow.Client with a simplified, gomobile-safe API.
type Client struct {
	cli       *whatsmeow.Client
	container *sqlstore.Container
	db        *sql.DB
	listener  WaEventListener
	qrCancel  context.CancelFunc
	mu        sync.Mutex
}

// NewClient initializes a new WhatsApp Gateway client with local SQLite session storage.
func NewClient(dbPath string, listener WaEventListener) (*Client, error) {
	logger := waLog.Stdout("WAGateway", "INFO", true)
	db, container, device, err := initStore(dbPath, logger)
	if err != nil {
		return nil, err
	}

	cli := whatsmeow.NewClient(device, logger)
	client := &Client{
		cli:       cli,
		container: container,
		db:        db,
		listener:  listener,
	}

	cli.AddEventHandler(func(rawEvt interface{}) {
		client.handleEvent(rawEvt)
	})

	return client, nil
}

func (c *Client) handleEvent(rawEvt interface{}) {
	if c.listener == nil {
		return
	}

	switch evt := rawEvt.(type) {
	case *events.Connected:
		c.listener.OnConnectionStatus("Connected")
	case *events.Disconnected:
		c.listener.OnConnectionStatus("Disconnected")
	case *events.LoggedOut:
		c.listener.OnConnectionStatus("Logged out")
	case *events.Message:
		// Never forward our own outgoing messages back into the agent, otherwise the
		// agent would reply to itself. whatsmeow exposes this through Info.IsFromMe,
		// so no guessing on the Kotlin side is required.
		if evt.Info.IsFromMe {
			return
		}

		var text string
		if evt.Message != nil {
			text = evt.Message.GetConversation()
			if text == "" && evt.Message.GetExtendedTextMessage() != nil {
				text = evt.Message.GetExtendedTextMessage().GetText()
			}
		}
		if text == "" {
			return
		}

		// Info.Sender is the author of the message (the participant inside a group),
		// while Info.Chat is the conversation that must be used when replying.
		sender := evt.Info.Sender.ToNonAD().String()
		chat := evt.Info.Chat.ToNonAD().String()
		c.listener.OnMessage(sender, chat, evt.Info.IsGroup, text, evt.Info.Timestamp.Unix())
	}
}

// Connect establishes the connection to WhatsApp. If not logged in, it initiates the QR channel.
func (c *Client) Connect() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.cli == nil {
		return errors.New("client not initialized")
	}

	if c.cli.IsConnected() {
		if c.listener != nil {
			c.listener.OnConnectionStatus("Connected")
		}
		return nil
	}

	if c.cli.IsLoggedIn() {
		if c.listener != nil {
			c.listener.OnConnectionStatus("Connecting")
		}
		err := c.cli.Connect()
		if err != nil {
			if c.listener != nil {
				c.listener.OnConnectionStatus(fmt.Sprintf("Error: %v", err))
			}
			return err
		}
		return nil
	}

	// Not logged in: listen for QR code
	if c.listener != nil {
		c.listener.OnConnectionStatus("Waiting for QR")
	}

	ctx, cancel := context.WithCancel(context.Background())
	c.qrCancel = cancel

	qrChan, err := c.cli.GetQRChannel(ctx)
	if err != nil {
		cancel()
		if c.listener != nil {
			c.listener.OnConnectionStatus(fmt.Sprintf("Error: %v", err))
		}
		return fmt.Errorf("failed to get QR channel: %w", err)
	}

	err = c.cli.Connect()
	if err != nil {
		cancel()
		if c.listener != nil {
			c.listener.OnConnectionStatus(fmt.Sprintf("Error: %v", err))
		}
		return fmt.Errorf("failed to connect: %w", err)
	}

	go func() {
		for item := range qrChan {
			switch item.Event {
			case "code":
				if c.listener != nil {
					c.listener.OnQRCode(item.Code)
					c.listener.OnConnectionStatus("Waiting for QR")
				}
			case "success":
				if c.listener != nil {
					c.listener.OnConnectionStatus("Connected")
				}
			case "timeout":
				if c.listener != nil {
					c.listener.OnConnectionStatus("QR Timeout")
				}
			case "error":
				if c.listener != nil {
					errStr := "QR pairing error"
					if item.Error != nil {
						errStr = item.Error.Error()
					}
					c.listener.OnConnectionStatus(fmt.Sprintf("Error: %s", errStr))
				}
			}
		}
	}()

	return nil
}

// PairPhone requests an 8-digit pairing code from WhatsApp to link via phone number instead of scanning QR.
func (c *Client) PairPhone(phone string) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.cli == nil {
		return "", errors.New("client not initialized")
	}

	if c.cli.IsLoggedIn() {
		return "", errors.New("already logged in")
	}

	// Clean phone number (strip '+', spaces, dashes)
	var sb strings.Builder
	for _, r := range phone {
		if unicode.IsDigit(r) {
			sb.WriteRune(r)
		}
	}
	cleaned := sb.String()
	if len(cleaned) <= 6 {
		return "", errors.New("phone number is too short")
	}
	if strings.HasPrefix(cleaned, "0") {
		return "", errors.New("phone number must include international country code (e.g. 628...)")
	}

	// Ensure connected to WhatsApp websocket
	if !c.cli.IsConnected() {
		if c.listener != nil {
			c.listener.OnConnectionStatus("Connecting for pairing code...")
		}
		if c.qrCancel != nil {
			c.qrCancel()
		}
		ctx, cancel := context.WithCancel(context.Background())
		c.qrCancel = cancel

		qrChan, err := c.cli.GetQRChannel(ctx)
		if err != nil {
			cancel()
			return "", fmt.Errorf("failed to get pairing channel: %w", err)
		}

		err = c.cli.Connect()
		if err != nil {
			cancel()
			return "", fmt.Errorf("failed to connect: %w", err)
		}

		// Wait for first QR event to ensure websocket connection is ready
		select {
		case item, ok := <-qrChan:
			if !ok {
				return "", errors.New("pairing channel closed unexpectedly")
			}
			if item.Event == "error" {
				return "", fmt.Errorf("connection error: %v", item.Error)
			}
		case <-time.After(15 * time.Second):
			return "", errors.New("timeout waiting for connection to establish")
		}

		// Continue listening to channel so success/timeout events are reported
		go func() {
			for item := range qrChan {
				switch item.Event {
				case "success":
					if c.listener != nil {
						c.listener.OnConnectionStatus("Connected")
					}
				case "timeout":
					if c.listener != nil {
						c.listener.OnConnectionStatus("Pairing Timeout")
					}
				case "error":
					if c.listener != nil {
						errStr := "Pairing error"
						if item.Error != nil {
							errStr = item.Error.Error()
						}
						c.listener.OnConnectionStatus(fmt.Sprintf("Error: %s", errStr))
					}
				}
			}
		}()
	}

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	code, err := c.cli.PairPhone(ctx, cleaned, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		if c.listener != nil {
			c.listener.OnConnectionStatus(fmt.Sprintf("Pair error: %v", err))
		}
		return "", fmt.Errorf("failed to request pairing code: %w", err)
	}

	if c.listener != nil {
		c.listener.OnPairingCode(code)
		c.listener.OnConnectionStatus("Waiting for code entry")
	}

	return code, nil
}

// Disconnect closes the active connection.
func (c *Client) Disconnect() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.qrCancel != nil {
		c.qrCancel()
		c.qrCancel = nil
	}

	if c.cli != nil && c.cli.IsConnected() {
		c.cli.Disconnect()
	}

	if c.listener != nil {
		c.listener.OnConnectionStatus("Disconnected")
	}
}

// IsLoggedIn checks whether a valid WhatsApp session exists.
func (c *Client) IsLoggedIn() bool {
	if c.cli == nil {
		return false
	}
	return c.cli.IsLoggedIn()
}

// IsConnected checks whether the client is currently connected.
func (c *Client) IsConnected() bool {
	if c.cli == nil {
		return false
	}
	return c.cli.IsConnected()
}

// SendText sends a plain text message to the specified recipient phone number or JID.
func (c *Client) SendText(target string, text string) error {
	if c.cli == nil || !c.cli.IsConnected() {
		return errors.New("client is not connected")
	}
	if strings.TrimSpace(target) == "" {
		return errors.New("target recipient cannot be empty")
	}
	if strings.TrimSpace(text) == "" {
		return errors.New("message text cannot be empty")
	}

	var recipient types.JID
	if strings.Contains(target, "@") {
		var err error
		recipient, err = types.ParseJID(target)
		if err != nil {
			return fmt.Errorf("invalid JID: %w", err)
		}
	} else {
		// Clean phone number (strip '+', spaces, dashes)
		var sb strings.Builder
		for _, r := range target {
			if unicode.IsDigit(r) {
				sb.WriteRune(r)
			}
		}
		cleaned := sb.String()
		if cleaned == "" {
			return errors.New("invalid phone number")
		}
		recipient = types.NewJID(cleaned, types.DefaultUserServer)
	}

	msg := &waE2E.Message{
		Conversation: proto.String(text),
	}

	_, err := c.cli.SendMessage(context.Background(), recipient, msg)
	if err != nil {
		return fmt.Errorf("failed to send message: %w", err)
	}

	return nil
}

// Logout logs out the current session and resets session credentials.
func (c *Client) Logout() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.qrCancel != nil {
		c.qrCancel()
		c.qrCancel = nil
	}

	if c.cli != nil {
		err := c.cli.Logout(context.Background())
		if c.listener != nil {
			c.listener.OnConnectionStatus("Logged out")
		}
		return err
	}
	return nil
}

// Close closes the underlying SQLite database connection.
func (c *Client) Close() {
	c.Disconnect()
	if c.container != nil {
		_ = c.container.Close()
	}
	if c.db != nil {
		_ = c.db.Close()
	}
}
