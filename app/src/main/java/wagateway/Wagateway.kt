package wagateway

object Wagateway {
    @JvmStatic
    fun newClient(dbPath: String, listener: WaEventListener?): Client {
        return Client(dbPath, listener)
    }
}
