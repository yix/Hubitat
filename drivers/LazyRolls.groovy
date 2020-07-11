metadata {
    definition(name: "LazyRolls", namespace: "McGunn", author: "Alex McGunn", importURL: "") {
        capability "Initialize"
        capability "WindowShade"
        capability "Actuator"

        command "close"
        command "open"
        command "stop"
        command "setPosition"

        attribute "position", "number"
        attribute "lastDirection", "ENUM", ["opening", "closing"]
        attribute "windowShade", "ENUM", ["opening", "partially open", "closed", "open", "closing", "unknown"]
    }

    preferences {
        input name: "ip", type: "text", title: "LazyRoll Address:", required: true, displayDuringSetup: true
        input("logEnable", "bool", title: "Enable logging", required: true, defaultValue: true)
    }
}


def installed() {
    log.info "installed..."
}

def updated() {
    if (logEnable) log.info "Updated..."
    initialize()
}

def uninstalled() {
    if (logEnable) log.info "Disconnecting from mqtt"
    interfaces.mqtt.disconnect()
}

def initialize() {
    if (!ip) {
        log.error "LazyRolls IP not configured!"
        return
    } else {
        log.info "LazyRolls initialized with IP ${ip}"
    }
}

def getStatus(args = [position: null, seq: 1]) {
    if (logEnable) log.debug "LazyRolls: getStatus(pos: ${position}, seq: ${args.seq})"
    lastDirection = device.currentValue("lastDirection")
    if (!args.position) {
        args.position = device.currentValue("position")
    } else if (args.seq > 100) {
        log.warn "LazyRolls: Too many getStatus() attempts. Giving up."
        return
    }
    def params = [
            uri               : "http://${ip}/xml",
            contentType       : "application/xml",
            requestContentType: "text/plain",
            textParser        : false
    ]
    try {
        httpGet(params) { response ->
            if (!response.isSuccess()) {
                log.error "LazyRolls: HTTP error ${response.status}"
            } else {
                pos = response.data.Position
                newPosition = Math.round((pos.Now.toFloat() / pos.Max.toFloat() * 10000).toFloat()) / 100
                if (logEnable) log.debug "LazyRolls: Position changed from ${newPosition} to ${args.position}, seq: ${args.seq}"
                sendEvent(name: "position", value: newPosition)
                if (newPosition != args.position || args.seq < 5) {
                    // in progress
                    if (newPosition > args.position) {
                        sendEvent(name: "windowShade", value: "closing")
                    } else {
                        sendEvent(name: "windowShade", value: "opening")
                    }
                    runInMillis(1000, 'getStatus', [data: ["position": newPosition, "seq": args.seq + 1]])
                    return
                } else {
                    // done
                    if (logEnable) log.debug "Done"
                    if (newPosition == 0) sendEvent(name: "windowShade", value: "open")
                    if (newPosition > 0 && position < 100) sendEvent(name: "windowShade", value: "partially open")
                    if (newPosition == 100) sendEvent(name: "windowShade", value: "closed")
                    log.info "LazyRolls: Stopped at ${newPosition}"
                }
            }
        }
    } catch (Exception e) {
        log.error "Lazy call error: ${e}"
        return false;
    }
}

def lazyRollSet(Number position) {
    current = device.currentValue("position")
    log.info "LazyRolls: Moving from ${current} to ${position}"
    httpGet([
            uri               : "http://${ip}/set?pos=${position}",
            contentType       : "application/json",
            requestContentType: "application/x-www-form-urlencoded",
    ]) { resp ->
        if (resp.data && resp.data.response && logEnable) log.debug respData
    }

    if (position > current) {
        sendEvent(name: "lastDirection", value: "closing")
    } else {
        sendEvent(name: "lastDirection", value: "opening")
    }
    getStatus()
}

def setPosition(Number position) {
    if (!position && position != 0) {
        log.warn "LazyRolls: setPosition(position): position is not set"
        return
    }
    lazyRollSet(position)
}

def close() {
    if (logEnable) log.debug "close()"
    lazyRollSet(100)
}

def open() {
    if (logEnable) log.debug "open()"
    lazyRollSet(0)
}

def stop() {
    httpGet([
            uri               : "http://${ip}/stop",
            contentType       : "text/plain",
            requestContentType: "text/plain",
    ]) { resp ->
        if (resp.data && logEnable) log.debug "stop() response: ${resp.status}"
        getStatus()
    }
}
