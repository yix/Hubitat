
metadata {
	definition (name: "Tuya Three Button Switch", namespace: "McGunn", author: "McGunn") {
		capability "Battery"
		capability "PushableButton"
		capability "Sensor"

		attribute "lastCheckinEpoch", "String"
		attribute "lastCheckinTime", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressedEpoch", "String"
		attribute "buttonPressedTime", "String"

		// Tuya ZigBee 3 Gang Remote
		fingerprint profileId: "0104", inClusters: "0000,000A,0001,0006", outClusters: "0019", manufacturer: "_TZ3000_bi6lpsew", model: "TS0043", deviceJoinName: "Tuya ZigBee 3 Gang Remote"

		command "resetBatteryReplacedDate"
        command "init"
	}

	preferences {
		//Battery Voltage Range
		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.9). Default = 2.9 Volts", description: "", type: "decimal", range: "2..2.9"
		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.95 to 3.4). Default = 3.05 Volts", description: "", type: "decimal", range: "2.95..3.4"
		//Date/Time Stamp Events Config
		input name: "lastCheckinEnable", type: "bool", title: "Enable custom date/time stamp events for lastCheckin", description: ""
		input name: "otherDateTimeEnable", type: "bool", title: "Enable custom date/time stamp events for buttonPressed, buttonDoubleTapped, and buttonHeld", description: ""
		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
		//Firmware 2.0.5 Compatibility Fix Config
		input name: "oldFirmware", type: "bool", title: "DISABLE 2.0.5 firmware compatibility fix (for users of 2.0.4 or earlier)", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	displayDebugLog("Parsing message: ${description}")
    // "catchall: 0104 0006 01 01 0040 00 F463 01 00 0000 FD 00 00"
    // "catchall: 0104 0006 02 01 0040 00 F463 01 00 0000 FD 00 00"
    // "catchall: 0104 0006 03 01 0040 00 F463 01 00 0000 FD 00 00"
    Map map = [:]
    if (!description.split(" ")[3].trim().isInteger() || !(description ==~ '^catchall: 0104 0006.*')) {
        return map
    }
    def buttonPressed = Integer.parseInt(description.split(" ")[3].trim(),16)
    map = parseButtonMessage(buttonPressed, 1)
	if (map != [:]) {
		displayDebugLog("Creating event $map")
		return createEvent(map)
	} else
		return map
}

// Build event map based on button press
private parseButtonMessage(buttonNum, pressType) {
	def whichButton = [1: (state.numOfButtons == 1) ? "Button" : "Left button", 2: "Middle button", 3: "Right button"]
	def messageType = ["held", "pressed", "double-tapped"]
	def eventType = ["held", "pushed", "doubleTapped"]
	def timeStampType = ["Held", "Pressed", "DoubleTapped"]
	def descText = "${whichButton[buttonNum]} was ${messageType[pressType]} (Button $buttonNum ${eventType[pressType]})"
	displayInfoLog(descText)
	updateDateTimeStamp(timeStampType[pressType])
	return [
		name: eventType[pressType],
		value: buttonNum,
		isStateChange: true,
		descriptionText: descText
	]
}

// Generate buttonPressedEpoch/Time, buttonHeldEpoch/Time, or buttonReleasedEpoch/Time event for Epoch time/date app or Hubitat dashboard use
def updateDateTimeStamp(timeStampType) {
	if (otherDateTimeEnable) {
		displayDebugLog("Setting button${timeStampType}Epoch and button${timeStampType}Time to current date/time")
		sendEvent(name: "button${timeStampType}Epoch", value: now(), descriptionText: "Updated button${timeStampType}Epoch")
		sendEvent(name: "button${timeStampType}Time", value: new Date().toLocaleString(), descriptionText: "Updated button${timeStampType}Time")
	}
}

// Convert raw 4 digit integer voltage value into percentage based on minVolts/maxVolts range
private parseBattery(description) {
	displayDebugLog("Battery parse string = ${description}")
	def MsgLength = description.size()
	def rawValue
	for (int i = 4; i < (MsgLength-3); i+=2) {
		if (description[i..(i+1)] == "21") { // Search for byte preceeding battery voltage bytes
			rawValue = Integer.parseInt((description[(i+4)..(i+5)] + description[(i+2)..(i+3)]),16)
			break
		}
	}
	def rawVolts = rawValue / 1000
	def minVolts = voltsmin ? voltsmin : 2.9
	def maxVolts = voltsmax ? voltsmax : 3.05
	def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
	def roundedPct = Math.min(100, Math.round(pct * 100))
	def descText = "Battery level is ${roundedPct}% (${rawVolts} Volts)"
	displayInfoLog(descText)
	// lastCheckinEpoch is for apps that can use Epoch time/date and lastCheckinTime can be used with Hubitat Dashboard
	if (lastCheckinEnable) {
		sendEvent(name: "lastCheckinEpoch", value: now())
		sendEvent(name: "lastCheckinTime", value: new Date().toLocaleString())
	}
	def result = [
		name: 'battery',
		value: roundedPct,
		unit: "%",
		descriptionText: descText
	]
	return result
}

private def displayDebugLog(message) {
	if (debugLogging)
		log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired device" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date().format("MMM dd yyyy", location.timeZone))
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	init()
	state.prefsSetCount = 1
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	def nButtons = 0
	def zigbeeModel = device.data.model ? device.data.model : "unknown"
	displayInfoLog("Reported ZigBee model ID is $zigbeeModel")
	switch (zigbeeModel.length() > 16 ? zigbeeModel[0..16] : zigbeeModel) {
		case "TS0043":
			nButtons = 3
			break;
		case "unknown":
			log.warn "Reported device model is unknown"
			nButtons = 0
			break;
	}
	displayInfoLog("Number of buttons set to $nButtons")
	if (!state.numOfButtons) {
		sendEvent(name: "numberOfButtons", value: nButtons)
		displayInfoLog("Number of buttons set to $nButtons")
		state.numOfButtons = nButtons
	}
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
}
