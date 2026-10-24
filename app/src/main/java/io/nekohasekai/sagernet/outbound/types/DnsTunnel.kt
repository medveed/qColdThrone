package app.qcoldthrone.mobile.outbound.types
import app.qcoldthrone.mobile.outbound.*
import app.qcoldthrone.mobile.outbound.json.*
import app.qcoldthrone.mobile.outbound.link.*
import app.qcoldthrone.mobile.bg.carrier.*

/**
 * A DNS tunnel profile: DNSTT, VayDNS, Slipstream or MasterDNS. The fields are those of the dnst:// format
 * (net2share/dnst-url-spec); links of the apps that predate it are read too (slipnet://, masterdns://).
 * The resolvers are the profile's own; without them the network's DNS servers are used at launch.
 */
class DnsTunnel(type:String="dnstt"):Outbound(type),NativeCarrier {
    override val carrierKind get()=when(type) {
        "vaydns" -> CarrierKind.VAYDNS
        "slipstream" -> CarrierKind.SLIPSTREAM
        "masterdns" -> CarrierKind.MASTERDNS
        else -> CarrierKind.DNSTT
    }
    private var data=JsonObject()
    override fun displayType()=TITLES[type] ?: "DNS"
    override fun displayAddress()=data.string("domain")

    companion object {
        val TYPES=listOf("dnstt","vaydns","slipstream","masterdns")
        val TITLES=mapOf("dnstt" to "DNSTT","vaydns" to "VayDNS","slipstream" to "SlipStream","masterdns" to "MasterDNS")
        val BACKENDS=listOf("socks","ssh","shadowsocks")
        val RECORD_TYPES=listOf("txt","cname","a","aaaa","mx","ns","srv","null","caa")
        private const val PREFIX="dnst://"
        private val HEX64=Regex("[0-9a-fA-F]{64}")

        /** SlipNet's tunnel types that are DNS tunnels this client runs, by its export name. */
        private val SLIPNET_TYPES=mapOf("dnstt" to ("dnstt" to "socks"),"dnstt_ssh" to ("dnstt" to "ssh"),
            "vaydns" to ("vaydns" to "socks"),"vaydns_ssh" to ("vaydns" to "ssh"),
            "ss" to ("slipstream" to "socks"),"slipstream_ssh" to ("slipstream" to "ssh"))

        /** The network's DNS servers right now, for profiles that name no resolver. */
        fun systemResolvers():List<String> = runCatching {
            val connectivity=app.qcoldthrone.mobile.SagerNet.connectivity
            val network=app.qcoldthrone.mobile.SagerNet.underlyingNetwork ?: connectivity.activeNetwork
            connectivity.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { address ->
                address.hostAddress?.substringBefore('%')?.let { Hosts.displayAddress(it,53) }
            }
        }.getOrDefault(emptyList())
    }

    override fun parseFromJson(obj:JsonObject):Boolean {
        val kind=obj.string("type")
        if(kind !in TYPES) return false
        type=kind
        data=obj.copy();data.remove("type");data.remove("tag")
        name=obj.string("name").ifEmpty { obj.string("tag") };data.remove("name")
        data["domain"]=data.string("domain").trim().trimEnd('.')
        if(type!="masterdns" && !data.contains("backend")) data["backend"]=jsonObjectOf("type" to "socks")
        server=data.string("domain");serverPort=0
        return valid()
    }

    private fun valid():Boolean {
        val domain=data.string("domain")
        if(domain.isEmpty() || domain.length>253 || domain.any { it.isWhitespace() || it=='/' }) return false
        when(type) {
            "dnstt","vaydns" -> if(!data.string("pubkey").matches(HEX64)) return false
            "masterdns" -> if(data.string("mkey").isBlank() || data.int("encryption") !in 0..5) return false
        }
        if(type=="vaydns" && data.string("record_type").isNotEmpty() && data.string("record_type") !in RECORD_TYPES) return false
        if(type!="masterdns") {
            val backend=data.obj("backend")
            when(backend.string("type")) {
                "socks" -> {}
                "ssh" -> if(backend.string("user").isEmpty() || (backend.string("password").isEmpty() && backend.string("pk").isEmpty())) return false
                "shadowsocks" -> if(backend.string("password").isEmpty() || backend.string("method").isEmpty()) return false
                else -> return false
            }
        }
        return data.array("resolvers").strings().none { it.any { c -> c=='\n' || c=='\r' } }
    }

    override fun parseFromLink(link:String):Boolean {
        val text=link.trim()
        return try {
            when {
                text.startsWith(PREFIX) -> parseDnst(text.removePrefix(PREFIX))
                text.startsWith("slipnet://") -> parseSlipNet(text.removePrefix("slipnet://"))
                text.startsWith("masterdns://") -> parseWhiteDns(text.removePrefix("masterdns://"))
                else -> false
            }
        } catch(_:Exception) { false }
    }

    /** dnst:// in either form: the URL with the domain as its host, or base64url JSON (§2.2: no '/' in it). */
    private fun parseDnst(body:String):Boolean {
        val content=body.substringBefore('#')
        if(!content.contains('/')) {
            val json=JsonInput.parseObject(String(Base64Strict.decode(content.trimEnd('='),true) ?: return false,Charsets.UTF_8))
            val transport=json.obj("transport");val backend=json.obj("backend")
            val obj=jsonObjectOf("type" to transport.string("type"),"name" to json.string("tag"))
            for((key,value) in transport) if(key!="type") obj[key]=value
            if(backend.isNotEmpty()) obj["backend"]=backend.copy()
            return parseFromJson(obj)
        }
        val url=LinkParser.parse(PREFIX+body)
        val parts=url.path.trim('/').split('/')
        if(url.host.isEmpty() || parts.size<2) return false
        val q=url.query
        val obj=jsonObjectOf("type" to parts[0],"domain" to url.host,"name" to (url.fragment ?: ""))
        for(key in listOf("pubkey","cert","record-type","idle-timeout","keepalive","mkey")) if(q.has(key)) obj[key.replace('-','_')]=q.valueFully(key)
        if(q.has("dnstt-compat")) obj["dnstt_compat"]=q.value("dnstt-compat") in listOf("1","true")
        for(key in listOf("clientid-size","encryption")) if(q.has(key)) obj[key.replace('-','_')]=q.value(key).toIntOrNull() ?: return false
        val backend=jsonObjectOf("type" to parts[1])
        for(key in listOf("user","password","pk","method")) if(q.has(key)) backend[key]=q.valueFully(key)
        obj["backend"]=backend
        return parseFromJson(obj)
    }

    /** SlipNet's export: base64 of pipe-separated fields; new versions only append, so positions hold. */
    private fun parseSlipNet(body:String):Boolean {
        val f=String(Base64Strict.decode(body.substringBefore('#').trim()) ?: Base64Strict.decode(body.trim(),true) ?: return false,Charsets.UTF_8).split('|')
        if(f.size<11) return false
        val (kind,backendType)=SLIPNET_TYPES[f[1]] ?: return false
        fun field(i:Int)=f.getOrNull(i).orEmpty()
        fun b64(i:Int)=field(i).takeIf { it.isNotEmpty() }?.let { Base64Strict.decode(it)?.toString(Charsets.UTF_8) }.orEmpty()
        val resolvers=JsonArray()
        val authoritative=field(4).split(',').any { it.split(':').getOrNull(2)=="1" }
        when(field(22)) {
            "doh" -> field(21).takeIf { it.startsWith("https://") }?.let { resolvers.add(it) }
            else -> for(item in (field(4).ifEmpty { field(37) }).split(',')) {
                val parts=item.split(':');if(parts.size<2 || parts[0].isBlank()) continue
                val address=Hosts.displayAddress(parts[0],parts[1].toIntOrNull() ?: 53)
                resolvers.add(if(field(22)=="dot") "tls://$address" else address)
            }
        }
        val obj=jsonObjectOf("type" to kind,"name" to field(2),"domain" to field(3),"resolvers" to resolvers)
        if(kind!="slipstream") obj["pubkey"]=field(11)
        if(kind=="slipstream") {
            obj["authoritative"]=authoritative
            field(6).toIntOrNull()?.let { obj["keep_alive_interval"]=it }
            if(field(7) in listOf("bbr","dcubic")) obj["congestion_control"]=field(7)
        }
        if(kind=="vaydns") {
            obj["dnstt_compat"]=field(41)=="1"
            field(42).takeIf { it in RECORD_TYPES }?.let { obj["record_type"]=it }
            field(49).toIntOrNull()?.takeIf { it>0 }?.let { obj["clientid_size"]=it }
        }
        val backend=jsonObjectOf("type" to backendType)
        if(backendType=="ssh") {
            backend["user"]=field(15);backend["password"]=field(16)
            b64(24).takeIf { it.isNotBlank() }?.let { backend["pk"]=it }
        } else {
            if(field(12).isNotEmpty()) backend["user"]=field(12)
            if(field(13).isNotEmpty()) backend["password"]=field(13)
        }
        obj["backend"]=backend
        return parseFromJson(obj)
    }

    /** WhiteDNS's MasterDnsVPN profile link: base64url JSON of the whitedns.profile schema. */
    private fun parseWhiteDns(body:String):Boolean {
        val payload=body.substringBefore('#').substringBefore('?')
        val json=JsonInput.parseObject(String(Base64Strict.decode(payload.trimEnd('='),true) ?: Base64Strict.decode(payload) ?: return false,Charsets.UTF_8))
        if(json.string("schema")!="whitedns.profile") return false
        val profile=json.obj("profile");val server=profile.obj("server")
        return parseFromJson(jsonObjectOf("type" to "masterdns","name" to profile.string("name"),"domain" to server.string("domain"),
            "mkey" to server.string("encryption_key"),"encryption" to server.int("encryption_method")))
    }

    override fun exportToJson()=data.copy().also { it["type"]=type;if(name.isNotEmpty()) it["name"]=name }
    override fun exportIdentity()=data.copy().also { it["type"]=type }

    /** dnst:// (§2): the readable form, or base64url JSON when a certificate or key would not fit in a URL. */
    override fun exportToLink():String {
        if(!valid()) return ""
        val backend=data.obj("backend")
        if(data.string("cert").isNotEmpty() || backend.string("pk").isNotEmpty()) {
            val transport=JsonObject().also { t -> t["type"]=type;for((k,v) in data) if(k!="backend" && k!="resolvers") t[k]=v }
            val json=jsonObjectOf("transport" to transport,"backend" to if(type=="masterdns") jsonObjectOf("type" to "socks") else backend)
            if(name.isNotEmpty()) json["tag"]=name
            return PREFIX+Base64Strict.encode(json.toCompact(),urlSafe=true,padding=false)
        }
        val query=ArrayList<String>()
        fun add(key:String,value:String) { if(value.isNotEmpty()) query.add(key+"="+LinkCodec.percentEncodeAll(value)) }
        add("pubkey",data.string("pubkey"));add("record-type",data.string("record_type"))
        if(data.bool("dnstt_compat")) add("dnstt-compat","true")
        if(data.int("clientid_size")>0) add("clientid-size",data.int("clientid_size").toString())
        add("idle-timeout",data.string("idle_timeout"));add("keepalive",data.string("keepalive"))
        add("mkey",data.string("mkey"));if(type=="masterdns") add("encryption",data.int("encryption").toString())
        for(key in listOf("user","password","method")) add(key,backend.string(key))
        val path="/"+type+"/"+(if(type=="masterdns") "socks" else backend.string("type"))
        return PREFIX+data.string("domain")+path+(if(query.isEmpty()) "" else "?"+query.joinToString("&"))+
            (if(name.isEmpty()) "" else "#"+LinkCodec.encodeFragment(name))
    }

    override fun build(ctx:BuildContext)=BuildResult(JsonObject(),"${displayType()} requires a prepared native transport bridge")
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials)=data.copy().also {
        require(valid())
        it["type"]=type
        it["system_resolvers"]=JsonArray.of(*systemResolvers().toTypedArray())
        it["socks"]="127.0.0.1:$port";it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
    }
}
