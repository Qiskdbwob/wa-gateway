package wagateway

// WaEventListener defines the callback interface called from Go into Kotlin/Android.
type WaEventListener interface {
	OnQRCode(code string)
	OnPairingCode(code string)
	OnConnectionStatus(status string)
	OnMessage(sender string, text string, timestamp int64)
}
