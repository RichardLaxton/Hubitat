/*
 *	Copyright 2018 Steve White
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *	use this file except in compliance with the License. You may obtain a copy
 *	of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software
 *	distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *	WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *	License for the specific language governing permissions and limitations
 *	under the License.
 */
metadata 
{
	// Automatically generated. Make future change here.
	definition(name: "Ikuu Mercator GPO with Power Reporting", namespace: "rlaxton", author: "Richard Laxton")
	{
		capability "Actuator"
		capability "Switch"
		capability "PowerMeter"
		capability "Voltage Measurement"
        capability "CurrentMeter"
		capability "Configuration"
		capability "Refresh"
		capability "Sensor"
		
		attribute "ACFrequency", "number"
		attribute "RMSCurrent", "number"
		
		command "toggle"
		command "identify"
		command "resetToFactoryDefault"
        
		fingerprint profileId: "0104", inClusters: "0000,0004,0005,0006,0702,0B04,EF00,0003", outClusters: "0019,000A", manufacturer: "_TZ3210_raqjcxo5", model: "Double Power Point", deviceJoinName: "Smart GPO"
	}


	preferences
	{
		section()
		{
        		input "intervalMin", "number", title: "Minimum interval (seconds) between power, current and voltage reports:", defaultValue: 5, range: "1..600", required: false
        		input "intervalMax", "number", title: "Maximum interval (seconds) between power, current and voltage reports:", defaultValue: 600, range: "1..600", required: false
       			input "minDeltaW", "enum", title: "Amount of power (W) change required to trigger a report:", defaultValue: "1", options: ["1", "5", "10", "20", "50", "100", "500", "1000"], required: false
       			input "minDeltaV", "enum", title: "Amount of voltage (V) change required to trigger a report:", defaultValue: "1", options: ["1", "2", "5", "10"], required: false
       			input "minDeltaA", "enum", title: "Amount of current (A) change required to trigger a report:", defaultValue: "0.1", options: ["0.1", "0.5", "1", "2", "3", "5"], required: false
		}
		section
		{
			input "enableTrace", "bool", title: "Enable trace logging?", description: "Show high-level activities during the operation of the device?", defaultValue: false, required: false, multiple: false
			input "enableDebug", "bool", title: "Enable debug logging?", description: "Show detailed responses to device commands?", defaultValue: false, required: false, multiple: false
		}
	}
}


/*
	installed
    
	Doesn't do much other than call configure().
*/
def installed()
{
	initialize()
    configure()
}


/*
	Called when the preferences are saved
    
	Doesn't do much other than call configure().
*/
def updated()
{
    logInfo "Configuration saved"
	initialize()
    configure()
}

/*
	initialize
    
	Doesn't do much other than call configure().
*/
def initialize()
{
	state.lastSwitch = 0

	if (enableTrace || enableDebug)
	{
		logInfo "Verbose logging has been enabled for the next 30 minutes."
		runIn(1800, logsOff)
	}
}


/*
	parse
    
	Processes incoming zigbee messages from the Iris Smart Plug.
*/
def parse(String description)
{
	logTrace "Msg: Description is $description"

	def event = zigbee.getEvent(description)
	def msg = zigbee.parseDescriptionAsMap(description)

	logTrace "Parsed data...  Evt: ${event.name}, msg: ${msg}"

	// Hubitat does not seem to support power events
	if (event)
	{
		if (event.name == "power")
		{
			def value = (event.value.parseInt()) / 10
			event = createEvent(name: event.name, value: value, descriptionText: "${device.displayName} power is ${value} watts")
			logTrace "${device.displayName} power is ${value} watts"
		}
		else if (event.name == "switch")
		{
			def descriptionText = event.value == "on" ? "${device.displayName} is On" : "${device.displayName} is Off"
			event = createEvent(name: event.name, value: event.value, descriptionText: descriptionText)
			
			// Since the switch has reported that it is off it can't be using any power.  Set to zero in case the power report does not arrive, but do not report in event logs.
			if (event.value == "off") sendEvent(name: "power", value: "0", descriptionText: "${device.displayName} power is 0 watts")
            
            // Pass the switch event to the appropriate child device
            logDebug("Looking for child device ${getChildName(msg.sourceEndpoint)}");
            def cd = getChildDevice(getChildName(msg.sourceEndpoint))
            if (cd)
            {
                logDebug("Passing event to child ${cd.displayName}")
                
                cd.parse([event]);
            }

			// DEVICE HEALTH MONITOR: Switch state (on/off) should report every 10 mins or so, regardless of any state changes.
			// Capture the time of this message
			state.lastSwitch = now()
		}
	}
	
	// Handle interval-based power reporting
	else if (msg?.cluster == "0B04")
	{
		// Watts
		if (msg.attrId == "050B")
		{
			def value = Integer.parseInt(msg.value, 16)
			event = createEvent(name: "power", value: value, descriptionText: "${device.displayName} power is ${value} watts")
			logTrace "${device.displayName} power is ${value} watts"
		}

		// Volts
		else if (msg.attrId == "0505")
		{
			def value = Integer.parseInt(msg.value, 16)
			event = createEvent(name: "voltage", value: value, descriptionText: "${device.displayName} voltage is ${value} volts")
			logTrace "${device.displayName} voltage is ${value} volts"
		}

        // RMS Current
        else if (msg.attrId == "0508")
		{
			def value = Integer.parseInt(msg.value, 16) / 1000        // Current is measured in mA for the outlet
			event = createEvent(name: "amperage", value: value, descriptionText: "${device.displayName} RMS current is ${value} A")
			logTrace "${device.displayName} RMS current is ${value} A"
		}
	}

	// Handle everything else
	else
	{
		def cluster = zigbee.parse(description)

		if (cluster && cluster.clusterId == 0x0006 && (cluster.command == 0x07 || cluster.command == 0x0B))
		{
			if (cluster.data[0] == 0x00 || cluster.data[0] == 0x02)
			{
				// DEVICE HEALTH MONITOR: Switch state (on/off) should report every 10mins or so, regardless of any state changes.
				// Capture the time of this message
				state.lastSwitch = now()

				if (cluster.data[0] == 0x00) logDebug "ON/OFF REPORTING CONFIG RESPONSE: " + cluster
				if (cluster.data[0] == 0x02) logDebug "ON/OFF TOGGLE RESPONSE: " + cluster
			}
			else
			{
				logError "ON/OFF REPORTING CONFIG FAILED- error code:${cluster.data[0]}"
				event = null
			}
		}
		else if (cluster && cluster.clusterId == 0x0B04 && cluster.command == 0x07)
		{
			if (cluster.data[0] == 0x00)
			{
				// Get a power meter reading
				runIn(5, "refresh")
				logDebug "POWER REPORTING CONFIG RESPONSE: " + cluster
			}
			else
			{
				logError "POWER REPORTING CONFIG FAILED- error code:${cluster.data[0]}"
				event = null
			}
		}
		else if (cluster && cluster.clusterId == 0x0003 && cluster.command == 0x04)
		{
			logInfo "LOCATING DEVICE FOR 30 SECONDS"
		}

		else
		{
			logWarn "DID NOT PARSE MESSAGE for description : $description"
			logDebug "${cluster}"
		}
	}
	return event ? createEvent(event) : event
}


private onOffClusterCommand(int destEndpoint, int command)
{
    logInfo("onOffCLusterCommand(${destEndpoint}, ${command})")
    if (destEndpoint < 0x01 || destEndpoint > 0x02)
    {
        logError("Unknown target endpoint ${destEndpoint}")
        return;
    }
    
    if (command < 0x00 || command > 0x02)
    {
        logError("Unknown command for onOffCluster")
        return;
    }

    // Send command to on/off cluster (0x0006) on the appropriate target
    def cmd = "he cmd 0x${device.deviceNetworkId} 0x${destEndpoint} 0x${zigbee.ON_OFF_CLUSTER} 0x${command} {}, delay 1000"
    logDebug("Command is ${cmd}")
    return cmd
}

/*
	on
    
	Turns the specific targetEndpoint of the device on .

	Uses standard Zigbee on/off cluster.
*/
def on(int destEndpoint)
{
    onOffClusterCommand(destEndpoint, 0x01);
}

/*
	off
    
	Turns the specific targetEndpoint of the device on .

	Uses standard Zigbee on/off cluster.
*/
def off(int destEndpoint)
{
    onOffClusterCommand(destEndpoint, 0x00);
}

/*
	toggle
    
	Turns the specific targetEndpoint of the device on .

	Uses standard Zigbee on/off cluster.
*/
def toggle(int destEndpoint)
{
    onOffClusterCommand(destEndpoint, 0x02);
}

/*
 * Turn on all endpoints
 */
def on()
{
    [on(0x01), on(0x02)]
}


/*
 * Turn off all endpoints
*/
def off()
{
    [off(0x01), off(0x02)]
}


/*
	Toggle all endpoints
*/
def toggle()
{
    [toggle(0x01), toggle(0x02)]
}

/*
	identify
    
	Flashes the blue LED on the plug to identify it.

*/
def identify()
{
	zigbee.writeAttribute(0x0003, 0x0000, DataType.UINT16, 0x00A)
}


/*
	resetToFactoryDefault
    
	Resets the plug to factory defaults but does not unpair the device.

*/
def resetToFactoryDefault()
{
	logWarn "Resetting device to factory defaults..."
	zigbee.command(0x0000, 0x00)
	
	runIn(10, configure)
}


/*
	refresh
    
	Refreshes the device by requesting manufacturer-specific information.

	Note: This is called from the refresh capbility
*/
def refresh()
{
	logDebug "Refresh called..."
    logDebug zigbee.onOffRefresh()
    logDebug zigbee.readAttribute(0xB77D, 0x02)
    logDebug zigbee.readAttribute(0x0B04, 0x0505)
	zigbee.onOffRefresh() +                 // First switch
        zigbee.readAttribute(0xB77D, 0x02) +    // Second switch
        zigbee.electricMeasurementPowerRefresh() + zigbee.readAttribute(0x0B04, 0x0505) + zigbee.readAttribute(0x0B04, 0x0508)
}

def getDestinationEndpoint(childDevice)
{
    def epId = childDevice.deviceNetworkId.split('-')[1]
    logDebug("EP id is ${epId}")
    
    return Integer.parseInt(epId, 16)
}

def getChildName(destinationEndpoint)
{
    if (destinationEndpoint)
    {
        def destEndpoint = (destinationEndpoint instanceof Integer) ? 
            destinationEndpoint :
            Integer.parseInt(destinationEndpoint, 16)
        
        return "${device.id}-${destEndpoint}"
    }
}

def componentOn(child)
{
    logDebug("componentOn(${child.deviceNetworkId})")
    sendHubCommand(new hubitat.device.HubAction(on(getDestinationEndpoint(child)), hubitat.device.Protocol.ZIGBEE))
}

def componentOff(child)
{
    logDebug("componentOff(${child.deviceNetworkId})")
    sendHubCommand(new hubitat.device.HubAction(off(getDestinationEndpoint(child)), hubitat.device.Protocol.ZIGBEE))
}

def componentRefresh(child)
{
    logDebug("componentRefresh(${child.deviceNetworkId})")
    refresh()
}


def deleteChildDevices()
{
    for (child in getChildDevices())
    {
        deleteChildDevice(child.deviceNetworkId);
    }
}

def createChildDevices()
{
    for (destEndpoint in [1, 2])
    {
        def childDeviceNetworkId = getChildName(destEndpoint)
        def cd = getChildDevice(childDeviceNetworkId)
        if (!cd)
        {
            cd = addChildDevice("hubitat", "Generic Component Switch", childDeviceNetworkId, [name: "${device.displayName} EP${destEndpoint}", isComponent: true])
            cd.parse([[name:"switch", value:"off", descriptionText:"set initial switch value"]])
        }
    }
    
    refresh();
}
                                               
                                               
/*
	configure
    
	Configures the Z-Wave repeater associations and establishes periodic device check.
*/
def configure()
{
	logDebug "Configure called..."
    
    deleteChildDevices()
    createChildDevices()
    
	// On/Off reporting of 0 seconds, maximum of 15 minutes if the device does not report any on/off activity
    logDebug(zigbee.onOffConfig(717, 900))
	zigbee.onOffConfig(0, 900) + powerConfig()
}


/*
	powerConfig
    
	Set power reporting configuration for devices with min reporting interval as 5 seconds and reporting interval if no activity as 10min (600s),
	if not otherwise specified.
*/
def powerConfig()
{
	// Calculate threshold
	def powerDelta = (int) Float.parseFloat(minDeltaW ?: "1")                // Power is in watts
	def currentDelta = (int) (Float.parseFloat(minDeltaA ?: "0.1") * 1000)     // Power is in mW of a watt
	
	logDebug "Configuring power reporting intervals; min: ${intervalMin}, max: ${intervalMax}, deltaW: ${powerDelta}, deltaA: ${currentDelta}, endpointId: ${endpointId}"

    def cfg = []

    // All reporting shares the same interval between 5 seconds and 3 minutes by default, override in settings
	cfg +=	zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, (int) (intervalMin ?: 5), (int) (intervalMax ?: 600), (int) powerDelta)	    // Wattage report
	cfg +=	zigbee.configureReporting(0x0B04, 0x0505, DataType.UINT16, (int) (intervalMin ?: 5), (int) (intervalMax ?: 600), 1)				    // Voltage report
    cfg +=  zigbee.configureReporting(0x0B04, 0x0508,  DataType.UINT16, (int) (intervalMin ?: 5), (int) (intervalMax ?: 600), (int) currentDelta);  // RMS Current

	return cfg
}


/*
	getEndpointId
    
	Helper function to get device endpoint (hex) as a String.
*/
private getEndpointId()
{
	new BigInteger(device.endpointId, 16).toString()
}


/*
	logError
    
	Displays dewarningbug output to IDE logs based on user preference.
*/
private logError(msgOut)
{
	log.error msgOut
}


/*
	logWarn
    
	Displays dewarningbug output to IDE logs based on user preference.
*/
private logWarn(msgOut)
{
		log.warn msgOut
}


/*
	logDebug
    
	Displays debug output to IDE logs based on user preference.
*/
private logDebug(msgOut)
{
	if (settings.enableDebug)
	{
		log.debug msgOut
	}
}


/*
	logTrace
    
	Displays trace output to IDE logs based on user preference.
*/
private logTrace(msgOut)
{
	if (settings.enableTrace)
	{
		log.trace msgOut
	}
}


/*
	logInfo
    
	Displays informational output to IDE logs.
*/
private logInfo(msgOut)
{
	log.info msgOut
}


/*
	logsOff
    
	Disables debug logging.
*/
def logsOff()
{
    logWarn "debug logging disabled..."
    device.updateSetting("enableTrace", [value:"false",type:"bool"])
	device.updateSetting("enableDebug", [value:"false",type:"bool"])
}
