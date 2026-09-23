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

	// kept so the client can be rebuilt on a clean device after a session reset
	dbPath string
	logger waLog.Logger
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
		dbPath:    dbPath,
		logger:    logger,
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
		// Info.ID is forwarded so Kotlin can mark the message as read (centang biru)
		// and, later on, edit it once the agent has produced a reply.
		c.listener.OnMessage(sender, chat, evt.Info.IsGroup, text, string(evt.Info.ID), evt.Info.Timestamp.Unix())
	}
}

// Connect establishes the connection to WhatsApp. If a linked session exists in the
// local store it is resumed, otherwise the QR pairing channel is started.
//
// It uses Store.ID (not IsLoggedIn) to decide which path to take: in this version of
// whatsmeow IsLoggedIn() is a runtime flag that only becomes true once the socket has
// authenticated, so right after a process restart it is still false even though a
// session is stored. Requesting GetQRChannel with a stored user ID fails with
// ErrQRStoreContainsID ("GetQRChannel can only be called when there's no user ID in
// the client's Store"), which is what broke session persistence before.
func (c *Client) Connect() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.connectLocked()
}

func (c *Client) connectLocked() error {
	if c.cli == nil {
		return errors.New("client not initialized")
	}

	if c.cli.IsConnected() {
		if c.listener != nil {
			c.listener.OnConnectionStatus("Connected")
		}
		return nil
	}

	// A stored user ID means this device is already linked to a WhatsApp account:
	// resume it instead of asking for a new QR code.
	if c.cli.Store.ID != nil {
		if c.listener != nil {
			c.listener.OnConnectionStatus("Menyambungkan ulang sesi tersimpan...")
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

	// No stored session: listen for QR code
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

	// Store.ID is the reliable "device is linked" check (see Connect). Re-pairing an
	// already linked device is rejected by WhatsApp itself, so fail early with a
	// message the UI can act on instead of surfacing ErrQRStoreContainsID.
	if c.cli.Store.ID != nil {
		return "", errors.New("sesi sudah tertaut; pakai Hubungkan, atau putuskan sesi dulu untuk menautkan ulang")
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

// IsLoggedIn reports whether this client is currently authenticated with WhatsApp.
//
// Note that whatsmeow keeps this as a runtime flag: it is false right after the
// process starts, before Connect() has completed. Use HasSession() to ask whether a
// linked session is stored on disk.
func (c *Client) IsLoggedIn() bool {
	if c.cli == nil {
		return false
	}
	return c.cli.IsLoggedIn()
}

// HasSession reports whether a linked WhatsApp session is stored locally, i.e. the
// device does not need to be paired again. This stays true across app restarts, so it
// is what the UI should use to show "sesi tersimpan" and to enable auto-reconnect.
func (c *Client) HasSession() bool {
	if c.cli == nil {
		return false
	}
	return c.cli.Store.ID != nil
}

// IsConnected checks whether the client is currently connected.
func (c *Client) IsConnected() bool {
	if c.cli == nil {
		return false
	}
	return c.cli.IsConnected()
}

// resolveRecipient accepts either a full JID ("62812...@s.whatsapp.net") or a bare
// phone number and returns the JID to send to.
func resolveRecipient(target string) (types.JID, error) {
	if strings.TrimSpace(target) == "" {
		return types.EmptyJID, errors.New("target recipient cannot be empty")
	}

	if strings.Contains(target, "@") {
		jid, err := types.ParseJID(target)
		if err != nil {
			return types.EmptyJID, fmt.Errorf("invalid JID: %w", err)
		}
		return jid, nil
	}

	// Clean phone number (strip '+', spaces, dashes)
	var sb strings.Builder
	for _, r := range target {
		if unicode.IsDigit(r) {
			sb.WriteRune(r)
		}
	}
	cleaned := sb.String()
	if cleaned == "" {
		return types.EmptyJID, errors.New("invalid phone number")
	}
	return types.NewJID(cleaned, types.DefaultUserServer), nil
}

// parseJIDOrEmpty parses an optional JID, returning types.EmptyJID when it is blank or
// malformed. MarkRead accepts an empty sender for direct chats.
func parseJIDOrEmpty(raw string) types.JID {
	if strings.TrimSpace(raw) == "" {
		return types.EmptyJID
	}
	jid, err := types.ParseJID(raw)
	if err != nil {
		return types.EmptyJID
	}
	return jid
}

// SendText sends a plain text message and returns the WhatsApp message ID
// (SendResponse.ID). The ID is what MarkRead and EditText operate on.
func (c *Client) SendText(target string, text string) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}

	if strings.TrimSpace(text) == "" {
		return "", errors.New("message text cannot be empty")
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	msg := &waE2E.Message{
		Conversation: proto.String(text),
	}

	resp, err := cli.SendMessage(context.Background(), recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send message: %w", err)
	}

	return string(resp.ID), nil
}

// EditText replaces the content of a message we already sent (used for the
// "sedang berpikir..." placeholder that turns into the final answer).
//
// WhatsApp only allows edits for a limited window (whatsmeow exposes it as
// whatsmeow.EditWindow, 20 minutes), so callers must fall back to sending a new
// message when this returns an error.
func (c *Client) EditText(target string, messageID string, text string) error {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return errors.New("client is not connected")
	}
	if strings.TrimSpace(messageID) == "" {
		return errors.New("message ID cannot be empty")
	}
	if strings.TrimSpace(text) == "" {
		return errors.New("message text cannot be empty")
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return err
	}

	edit := cli.BuildEdit(recipient, types.MessageID(messageID), &waE2E.Message{
		Conversation: proto.String(text),
	})
	if _, err := cli.SendMessage(context.Background(), recipient, edit); err != nil {
		return fmt.Errorf("failed to edit message: %w", err)
	}
	return nil
}

// MarkRead sends a read receipt (centang biru) for a single incoming message.
//
// chat must be the conversation JID and sender the author; for direct chats both are
// the same user JID. An empty or invalid sender is tolerated.
func (c *Client) MarkRead(chat string, sender string, messageID string) error {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return errors.New("client is not connected")
	}
	if strings.TrimSpace(messageID) == "" {
		return errors.New("message ID cannot be empty")
	}

	chatJID := parseJIDOrEmpty(chat)
	if chatJID.IsEmpty() {
		return fmt.Errorf("invalid chat JID: %q", chat)
	}

	err := cli.MarkRead(
		context.Background(),
		[]types.MessageID{types.MessageID(messageID)},
		time.Now(),
		chatJID,
		parseJIDOrEmpty(sender),
	)
	if err != nil {
		return fmt.Errorf("failed to mark message as read: %w", err)
	}
	return nil
}

// SetTyping shows or clears the "typing..." indicator in a chat. WhatsApp expires the
// state on its own, so long running work must re-send composing periodically.
func (c *Client) SetTyping(chat string, typing bool) error {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return errors.New("client is not connected")
	}

	chatJID := parseJIDOrEmpty(chat)
	if chatJID.IsEmpty() {
		return fmt.Errorf("invalid chat JID: %q", chat)
	}

	state := types.ChatPresencePaused
	if typing {
		state = types.ChatPresenceComposing
	}

	if err := cli.SendChatPresence(context.Background(), chatJID, state, types.ChatPresenceMediaText); err != nil {
		return fmt.Errorf("failed to update typing state: %w", err)
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

	if c.cli == nil {
		return nil
	}

	err := c.cli.Logout(context.Background())
	if c.listener != nil {
		c.listener.OnConnectionStatus("Logged out")
	}

	// Logout deletes the device from the store, which makes the current whatsmeow
	// client unusable (its session stores are replaced with a NoopStore). Rebuild it
	// on a clean device so pairing can start again without restarting the app.
	if rebuildErr := c.rebuildLocked(); rebuildErr != nil && err == nil {
		err = rebuildErr
	}
	return err
}

// ResetSession unlinks the stored session without needing to be connected. Used by the
// "putuskan sesi" action so a device can be paired again from scratch.
func (c *Client) ResetSession() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.qrCancel != nil {
		c.qrCancel()
		c.qrCancel = nil
	}
	if c.cli == nil {
		return errors.New("client not initialized")
	}

	ctx := context.Background()
	var err error
	if c.cli.IsConnected() {
		// Ask WhatsApp to remove this companion device and wipe the local store.
		err = c.cli.Logout(ctx)
	} else {
		// Offline: drop the local device record so the next Connect() uses QR.
		err = c.cli.Store.Delete(ctx)
	}

	if rebuildErr := c.rebuildLocked(); rebuildErr != nil && err == nil {
		err = rebuildErr
	}

	if c.listener != nil {
		c.listener.OnConnectionStatus("Logged out")
	}
	return err
}

// rebuildLocked opens a fresh device (and whatsmeow client) inside the same SQLite
// database. Callers must hold c.mu.
func (c *Client) rebuildLocked() error {
	if c.cli != nil {
		c.cli.Disconnect()
	}
	if c.container != nil {
		_ = c.container.Close()
	}
	if c.db != nil {
		_ = c.db.Close()
	}

	db, container, device, err := initStore(c.dbPath, c.logger)
	if err != nil {
		return err
	}

	cli := whatsmeow.NewClient(device, c.logger)
	cli.AddEventHandler(func(rawEvt interface{}) {
		c.handleEvent(rawEvt)
	})

	c.db = db
	c.container = container
	c.cli = cli
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
