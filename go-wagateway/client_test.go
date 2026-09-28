package wagateway

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

// textMessage is the innermost content the tests dig out of the envelopes.
func textMessage(text string) *waE2E.Message {
	return &waE2E.Message{Conversation: proto.String(text)}
}

func imageMessage() *waE2E.Message {
	return &waE2E.Message{
		ImageMessage: &waE2E.ImageMessage{
			URL:      proto.String("https://mmg.whatsapp.net/v/t62/photo"),
			Mimetype: proto.String("image/jpeg"),
			MediaKey: []byte{1, 2, 3},
		},
	}
}

func documentMessage() *waE2E.Message {
	return &waE2E.Message{
		DocumentMessage: &waE2E.DocumentMessage{
			URL:      proto.String("https://mmg.whatsapp.net/v/t62/doc"),
			Mimetype: proto.String("application/pdf"),
			FileName: proto.String("laporan.pdf"),
			MediaKey: []byte{4, 5, 6},
		},
	}
}

// wrapEphemeral builds the disappearing-messages envelope. WhatsApp wraps every envelope
// with the same FutureProofMessage type (ephemeral, view once, document with caption),
// which is why one helper covers them all.
func wrapEphemeral(inner *waE2E.Message) *waE2E.Message {
	return &waE2E.Message{EphemeralMessage: &waE2E.FutureProofMessage{Message: inner}}
}

// TestUnwrapMessageFindsInnerContent covers the envelopes that made messages look empty to
// the agent: disappearing chats (ephemeral), view-once media and documents with a caption.
func TestUnwrapMessageFindsInnerContent(t *testing.T) {
	tests := []struct {
		name    string
		wrapped *waE2E.Message
		check   func(t *testing.T, got *waE2E.Message)
	}{
		{
			name:    "plain text is returned unchanged",
			wrapped: textMessage("halo"),
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetConversation() != "halo" {
					t.Fatalf("conversation = %q, want %q", got.GetConversation(), "halo")
				}
			},
		},
		{
			name:    "plain image is returned unchanged",
			wrapped: imageMessage(),
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetImageMessage().GetMimetype() != "image/jpeg" {
					t.Fatalf("mimetype = %q, want image/jpeg", got.GetImageMessage().GetMimetype())
				}
			},
		},
		{
			name:    "ephemeral text",
			wrapped: wrapEphemeral(textMessage("pesan sementara")),
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetConversation() != "pesan sementara" {
					t.Fatalf("conversation = %q, want %q", got.GetConversation(), "pesan sementara")
				}
			},
		},
		{
			name: "view once image",
			wrapped: &waE2E.Message{
				ViewOnceMessage: &waE2E.FutureProofMessage{Message: imageMessage()},
			},
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetImageMessage() == nil {
					t.Fatal("view-once photo did not unwrap to an image message")
				}
			},
		},
		{
			name: "view once v2 image",
			wrapped: &waE2E.Message{
				ViewOnceMessageV2: &waE2E.FutureProofMessage{Message: imageMessage()},
			},
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetImageMessage() == nil {
					t.Fatal("view-once v2 photo did not unwrap to an image message")
				}
			},
		},
		{
			name: "view once v2 extension image",
			wrapped: &waE2E.Message{
				ViewOnceMessageV2Extension: &waE2E.FutureProofMessage{Message: imageMessage()},
			},
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetImageMessage() == nil {
					t.Fatal("view-once v2 extension photo did not unwrap to an image message")
				}
			},
		},
		{
			name: "document with caption",
			wrapped: &waE2E.Message{
				DocumentWithCaptionMessage: &waE2E.FutureProofMessage{Message: documentMessage()},
			},
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetDocumentMessage().GetFileName() != "laporan.pdf" {
					t.Fatalf("filename = %q, want laporan.pdf", got.GetDocumentMessage().GetFileName())
				}
			},
		},
		{
			name:    "nested envelopes are opened one after another",
			wrapped: wrapEphemeral(wrapEphemeral(imageMessage())),
			check: func(t *testing.T, got *waE2E.Message) {
				if got.GetImageMessage() == nil {
					t.Fatal("nested envelopes did not unwrap to the image message")
				}
			},
		},
		{
			name:    "nil message stays nil",
			wrapped: nil,
			check: func(t *testing.T, got *waE2E.Message) {
				if got != nil {
					t.Fatalf("got %v, want nil", got)
				}
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			tc.check(t, unwrapMessage(tc.wrapped))
		})
	}
}

// TestUnwrapMessageRespectsDepthCap keeps a malformed payload from spinning forever: four
// levels are opened, deeper nesting is returned as is.
func TestUnwrapMessageRespectsDepthCap(t *testing.T) {
	four := textMessage("halo")
	for i := 0; i < 4; i++ {
		four = wrapEphemeral(four)
	}
	if got := unwrapMessage(four).GetConversation(); got != "halo" {
		t.Fatalf("four levels: conversation = %q, want halo", got)
	}

	deeper := four
	for i := 0; i < 3; i++ {
		deeper = wrapEphemeral(deeper)
	}
	got := unwrapMessage(deeper)
	if got == nil {
		t.Fatal("seven levels returned nil")
	}
	if got.GetEphemeralMessage() == nil {
		t.Fatal("expected the depth cap to leave the remaining envelope in place")
	}
}

// TestInnerMediaPayloadNeedsConcreteType pins the root cause of the old
// "no downloadable media found in payload" failure: handleEvent marshals the INNER message,
// which never populates waE2E.Message.ImageMessage, while unmarshalling into the concrete
// ImageMessage type works.
func TestInnerMediaPayloadNeedsConcreteType(t *testing.T) {
	payload, err := proto.Marshal(imageMessage().GetImageMessage())
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	// Reading it back as the outer message fails one of two ways depending on the payload
	// (wire-format error, or a partial message that never carries the image), and both make
	// the media unreachable. Either outcome is the bug, so neither may turn into a usable image.
	var outer waE2E.Message
	outerErr := proto.Unmarshal(payload, &outer)
	if outerErr == nil && outer.GetImageMessage() != nil {
		t.Fatal("an inner ImageMessage payload unexpectedly filled Message.ImageMessage")
	}

	var inner waE2E.ImageMessage
	if err := proto.Unmarshal(payload, &inner); err != nil {
		t.Fatalf("unmarshal into ImageMessage: %v", err)
	}
	if inner.GetURL() != "https://mmg.whatsapp.net/v/t62/photo" || len(inner.GetMediaKey()) == 0 {
		t.Fatalf("concrete unmarshal lost the download fields: url=%q keyLen=%d", inner.GetURL(), len(inner.GetMediaKey()))
	}
}
