metadata {
        definition (name: "SP103 z-wave", author: "Ivan Airola") {
                capability "Contact Sensor"
                capability "Sensor"
                capability "Battery"
        }

        simulator {
                // These show up in the IDE simulator "messages" drop-down to test sending event messages to your device handler
                status "dimmer switch on at 70%" : zwave.switchMultilevelV1.switchMultilevelReport(value:70).incomingMessage()
                status "basic set on"                    : zwave.basicV1.basicSet(value:0xFF).incomingMessage()
                status "low battery alert"               : zwave.batteryV1.batteryReport(batteryLevel:0xFF).incomingMessage()             
        }

        tiles {
                standardTile("sensor", "device.sensor", width: 2, height: 2) {
			state("inactive", label:'inactive', icon:"st.unknown.zwave.sensor", backgroundColor:"#cccccc")
			state("active", label:'active', icon:"st.unknown.zwave.sensor", backgroundColor:"#00A0DC")
				}
                valueTile("battery", "device.battery", decoration: "flat") {
                        state "battery", label:'${currentValue}% battery', unit:""
                }
                standardTile("contact", "device.contact", width: 2, height: 2) {
			state "open", label: '${name}', icon: "st.contact.contact.open", backgroundColor: "#e86d13"
			state "closed", label: '${name}', icon: "st.contact.contact.closed", backgroundColor: "#00A0DC"
				}

                main (["sensor","contact"])
                details (["contact", "sensor", "battery"])
        }
}

def parse(String description) {
        def result = null
        def cmd = zwave.parse(description, [0x60: 3])
        if (cmd) {
                result = zwaveEvent(cmd)
                log.debug "Parsed ${cmd} to ${result.inspect()}"
        } else {
                log.debug "Non-parsed event: ${description}"
        }
        result
}




def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd)
{
        def result
        switch (cmd.sensorType) {
                case 8:
                        result = createEvent(name:"tamper", value: cmd.sensorValue ? "detected" : "okay")
                        break
                case 0x0A:
                        result = createEvent(name:"contact", value: cmd.sensorValue ? "open" : "closed")
                        break
                case 0x0B:
                        result = createEvent(name:"tilt", value: cmd.sensorValue ? "detected" : "okay")
                        break
                case 0x0C:
                        result = createEvent(name:"motion", value: cmd.sensorValue ? "active" : "inactive")
                        break
                default:
                        result = createEvent(name:"sensor", value: cmd.sensorValue ? "active" : "inactive")
                        break
        }
        result
}

def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv1.SensorBinaryReport cmd)
{
        // Version 1 of SensorBinary doesn't have a sensor type
        createEvent(name:"sensor", value: cmd.sensorValue ? "active" : "inactive")
}



// Many sensors send BasicSet commands to associated devices. This is so you can associate them with
// a switch-type device and they can directly turn it on/off when the sensor is triggered.
def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd)
{
        createEvent(name:"sensor", value: cmd.value ? "active" : "inactive")
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
        def map = [ name: "battery", unit: "%" ]
        if (cmd.batteryLevel == 0xFF) {  // Special value for low battery alert
                map.value = 1
                map.descriptionText = "${device.displayName} has a low battery"
                map.isStateChange = true
        } else {
                map.value = cmd.batteryLevel
        }
        // Store time of last battery update so we don't ask every wakeup, see WakeUpNotification handler
        state.lastbatt = new Date().time
        createEvent(map)
}

// Battery powered devices can be configured to periodically wake up and check in. They send this
// command and stay awake long enough to receive commands, or until they get a WakeUpNoMoreInformation
// command that instructs them that there are no more commands to receive and they can stop listening
def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
        def result = [createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)]

        // Only ask for battery if we haven't had a BatteryReport in a while
        if (!state.lastbatt || (new Date().time) - state.lastbatt > 24*60*60*1000) {
                result << response(zwave.batteryV1.batteryGet())
                result << response("delay 1200")  // leave time for device to respond to batteryGet
        }
        result << response(zwave.wakeUpV1.wakeUpNoMoreInformation())
        result
}

def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd) {
        def result = []
        if (cmd.nodeId.any { it == zwaveHubNodeId }) {
                result << createEvent(descriptionText: "$device.displayName is associated in group ${cmd.groupingIdentifier}")
        } else if (cmd.groupingIdentifier == 1) {
                // We're not associated properly to group 1, set association
                result << createEvent(descriptionText: "Associating $device.displayName in group ${cmd.groupingIdentifier}")
                result << response(zwave.associationV1.associationSet(groupingIdentifier:cmd.groupingIdentifier, nodeId:zwaveHubNodeId))
        }
        result
}

// Devices that support the Security command class can send messages in an encrypted form;
// they arrive wrapped in a SecurityMessageEncapsulation command and must be unencapsulated
def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
        def encapsulatedCommand = cmd.encapsulatedCommand([0x98: 1, 0x20: 1]) // can specify command class versions here like in zwave.parse
        if (encapsulatedCommand) {
                return zwaveEvent(encapsulatedCommand)
        }
}

// MultiChannelCmdEncap and MultiInstanceCmdEncap are ways that devices can indicate that a message
// is coming from one of multiple subdevices or "endpoints" that would otherwise be indistinguishable
def zwaveEvent(physicalgraph.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
        def encapsulatedCommand = cmd.encapsulatedCommand([0x30: 1, 0x31: 1]) // can specify command class versions here like in zwave.parse
        log.debug ("Command from endpoint ${cmd.sourceEndPoint}: ${encapsulatedCommand}")
        if (encapsulatedCommand) {
                return zwaveEvent(encapsulatedCommand)
        }
}

def zwaveEvent(physicalgraph.zwave.commands.multichannelv3.MultiInstanceCmdEncap cmd) {
        def encapsulatedCommand = cmd.encapsulatedCommand([0x30: 1, 0x31: 1]) // can specify command class versions here like in zwave.parse
        log.debug ("Command from instance ${cmd.instance}: ${encapsulatedCommand}")
        if (encapsulatedCommand) {
                return zwaveEvent(encapsulatedCommand)
        }
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
        createEvent(descriptionText: "${device.displayName}: ${cmd}")
}


// If you add the Polling capability to your device type, this command will be called approximately
// every 5 minutes to check the device's state
def poll() {
        zwave.basicV1.basicGet().format()
}

// If you add the Configuration capability to your device type, this command will be called right
// after the device joins to set device-specific configuration commands.
def configure() {
        delayBetween([
                // Note that configurationSet.size is 1, 2, or 4 and generally must match the size the device uses in its configurationReport
                zwave.configurationV1.configurationSet(parameterNumber:1, size:2, scaledConfigurationValue:100).format(),
                // Can use the zwaveHubNodeId variable to add the hub to the device's associations:
                zwave.associationV1.associationSet(groupingIdentifier:2, nodeId:zwaveHubNodeId).format(),
                // Make sure sleepy battery-powered sensors send their WakeUpNotifications to the hub every 4 hours:
                zwave.wakeUpV1.wakeUpIntervalSet(seconds:4 * 3600, nodeid:zwaveHubNodeId).format(),
        ])
}