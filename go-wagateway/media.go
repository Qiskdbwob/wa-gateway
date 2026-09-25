package wagateway

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

// Media support (Phase 25 of DOC/context-2.md).
//
// The upload/download flow follows the official whatsmeow documentation (verified
// against the pinned whatsmeow version's upload.go / download.go):
//
//	resp, err := cli.Upload(ctx, data, whatsmeow.MediaImage)
//	imageMsg := &waE2E.ImageMessage{
//		URL: &resp.URL, DirectPath: &resp.DirectPath, MediaKey: resp.MediaKey,
//		FileEncSHA256: resp.FileEncSHA256, FileSHA256: resp.FileSHA256,
//		FileLength: &resp.FileLength, Mimetype: proto.String("image/png"),
//	}
//	cli.SendMessage(ctx, jid, &waE2E.Message{ImageMessage: imageMsg})
//
// Downloads use cli.Download(ctx, msg.GetImageMessage()) which verifies the
// SHA256 and decrypts the payload.
//
// Only gomobile-safe types ([]byte, string, int64) cross the Go <-> Kotlin boundary.

// SendImage uploads and sends a picture. caption may be empty. Returns the message ID.
func (c *Client) SendImage(target string, data []byte, mimetype string, caption string) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("image data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "image/jpeg"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaImage)
	if err != nil {
		return "", fmt.Errorf("failed to upload image: %w", err)
	}

	msg := &waE2E.Message{
		ImageMessage: &waE2E.ImageMessage{
			Caption:       proto.String(caption),
			Mimetype:      proto.String(mimetype),
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send image: %w", err)
	}
	return string(sendResp.ID), nil
}

// SendDocument uploads and sends a file as a WhatsApp document. filename is shown
// in the document bubble. Returns the message ID.
func (c *Client) SendDocument(target string, data []byte, mimetype string, filename string) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("document data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "application/octet-stream"
	}
	if strings.TrimSpace(filename) == "" {
		filename = "document"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaDocument)
	if err != nil {
		return "", fmt.Errorf("failed to upload document: %w", err)
	}

	msg := &waE2E.Message{
		DocumentMessage: &waE2E.DocumentMessage{
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
			Mimetype:      proto.String(mimetype),
			FileName:      proto.String(filename),
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send document: %w", err)
	}
	return string(sendResp.ID), nil
}

// SendAudio uploads and sends an audio clip. When voiceNote is true it is sent as a
// push-to-talk (voice note) bubble instead of a plain audio attachment.
func (c *Client) SendAudio(target string, data []byte, mimetype string, voiceNote bool) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("audio data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "audio/ogg"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaAudio)
	if err != nil {
		return "", fmt.Errorf("failed to upload audio: %w", err)
	}

	msg := &waE2E.Message{
		AudioMessage: &waE2E.AudioMessage{
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
			Mimetype:      proto.String(mimetype),
			PTT:           proto.Bool(voiceNote),
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send audio: %w", err)
	}
	return string(sendResp.ID), nil
}

// SendVideo uploads and sends a video clip. caption may be empty.
func (c *Client) SendVideo(target string, data []byte, mimetype string, caption string) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("video data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "video/mp4"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaVideo)
	if err != nil {
		return "", fmt.Errorf("failed to upload video: %w", err)
	}

	msg := &waE2E.Message{
		VideoMessage: &waE2E.VideoMessage{
			Caption:       proto.String(caption),
			Mimetype:      proto.String(mimetype),
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send video: %w", err)
	}
	return string(sendResp.ID), nil
}

// DownloadMedia downloads and decrypts the attachment of a received media message.
// mediaMessageProto is the marshalled waE2E.ImageMessage / AudioMessage /
// VideoMessage / DocumentMessage the Kotlin side got through OnMedia.
func (c *Client) DownloadMedia(mediaMessageProto []byte) ([]byte, error) {
	cli := c.cli
	if cli == nil {
		return nil, errors.New("client not initialized")
	}
	if len(mediaMessageProto) == 0 {
		return nil, errors.New("media payload cannot be empty")
	}

	var msg waE2E.Message
	if err := proto.Unmarshal(mediaMessageProto, &msg); err != nil {
		return nil, fmt.Errorf("invalid media payload: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	switch {
	case msg.GetImageMessage() != nil:
		return cli.Download(ctx, msg.GetImageMessage())
	case msg.GetAudioMessage() != nil:
		return cli.Download(ctx, msg.GetAudioMessage())
	case msg.GetVideoMessage() != nil:
		return cli.Download(ctx, msg.GetVideoMessage())
	case msg.GetDocumentMessage() != nil:
		return cli.Download(ctx, msg.GetDocumentMessage())
	default:
		return nil, errors.New("no downloadable media found in payload")
	}
}
