/**
 * Copyright (c) 2018-2019 SpringCard - www.springcard.com
 * All right reserved
 * This software is covered by the SpringCard SDK License Agreement - see LICENSE.txt
 */

package com.springcard.pcsclike

import android.bluetooth.*
import android.os.Build
import android.support.annotation.RequiresApi
import android.util.Log
import kotlin.experimental.and
import android.bluetooth.BluetoothDevice
import com.springcard.pcsclike.CCID.CcidFrame
import com.springcard.pcsclike.CCID.CcidResponse
import java.util.*
import kotlin.experimental.inv


@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class BluetoothLayer(internal var bluetoothDevice: BluetoothDevice, private var callbacks: SCardReaderListCallback, private var scardReaderList : SCardReaderList): CommunicationLayer(callbacks, scardReaderList) {

    private val TAG = this::class.java.simpleName
    private val lowLayer: BleLowLevel = BleLowLevel(this)


    private val uuidCharacteristicsToReadPower by lazy {
        mutableListOf<UUID>(
            GattAttributesSpringCore.UUID_BATTERY_POWER_STATE_CHAR,
            GattAttributesSpringCore.UUID_BATTERY_LEVEL_CHAR
        )
    }

    private val uuidCharacteristicsToRead by lazy {
        mutableListOf<UUID>(
            GattAttributesSpringCore.UUID_MODEL_NUMBER_STRING_CHAR,
            GattAttributesSpringCore.UUID_SERIAL_NUMBER_STRING_CHAR,
            GattAttributesSpringCore.UUID_FIRMWARE_REVISION_STRING_CHAR,
            GattAttributesSpringCore.UUID_HARDWARE_REVISION_STRING_CHAR,
            GattAttributesSpringCore.UUID_SOFTWARE_REVISION_STRING_CHAR,
            GattAttributesSpringCore.UUID_MANUFACTURER_NAME_STRING_CHAR,
            GattAttributesSpringCore.UUID_PNP_ID_CHAR,
            GattAttributesSpringCore.UUID_CCID_STATUS_CHAR
        )
    }

    private val uuidCharacteristicsCanIndicate  by lazy {
        mutableListOf<UUID>(
            GattAttributesSpringCore.UUID_CCID_STATUS_CHAR,
           GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR
        )
    }


    private var characteristicsToRead : MutableList<BluetoothGattCharacteristic> = mutableListOf<BluetoothGattCharacteristic>()
    private var characteristicsCanIndicate : MutableList<BluetoothGattCharacteristic> = mutableListOf<BluetoothGattCharacteristic>()
    private var characteristicsToReadPower : MutableList<BluetoothGattCharacteristic> = mutableListOf<BluetoothGattCharacteristic>()
    internal lateinit var charCcidPcToRdr : BluetoothGattCharacteristic



    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // Fail fast in case somebody ignored the @RequiresApi annotation
            throw UnsupportedOperationException("BLE not available on Android SDK < ${Build.VERSION_CODES.LOLLIPOP}")
        }
    }

    /* State machine */

    override fun process(event: ActionEvent) {

        Log.d(TAG, "Current state = ${currentState.name}")
        // Memo CRA : SCardDevice instance = 0x${System.identityHashCode(scardReaderList).toString(16).toUpperCase()}

        when (currentState) {
            State.Disconnected -> handleStateDisconnected(event)
            State.Connecting -> handleStateConnecting(event)
            State.DiscoveringGatt -> handleStateDiscovering(event)
            State.ReadingInformation -> handleStateReadingInformation(event)
            State.SubscribingNotifications -> handleStateSubscribingNotifications(event)
            State.ReadingSlotsName ->  handleStateReadingSlotsName(event)
            State.Authenticate -> handleStateAuthenticate(event)
            State.ConnectingToCard -> handleStateConnectingToCard(event)
            State.Idle ->  handleStateIdle(event)
            State.ReadingPowerInfo -> handleStateReadingPowerInfo(event)
            State.WritingCommand -> handleStateWritingCommand(event)
            State.WaitingResponse -> handleStateWaitingResponse(event)
            State.Disconnecting ->  handleStateDisconnecting(event)
            else -> Log.w(TAG,"Unhandled State : $currentState")
        }
    }

    private fun handleStateDisconnected(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.ActionCreate-> {
                currentState = State.Connecting
                lowLayer.connect(event.ctx)
                /* save context if we need to try to reconnect */
                context = event.ctx
            }
            else -> Log.e(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private fun handleStateConnecting(event: ActionEvent) {
        when (event) {
            is ActionEvent.EventConnected -> {
                Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
                currentState = State.DiscoveringGatt

                scardReaderList.isConnected = true

                lowLayer.discoverGatt()
                Log.i(TAG, "Attempting to start service discovery")
            }
            is ActionEvent.EventDisconnected -> {
                /* Retry connecting */
                currentState = State.Disconnected
                process(ActionEvent.ActionCreate(context))
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private fun handleStateDiscovering(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventServicesDiscovered -> {
                if (event.status == BluetoothGatt.GATT_SUCCESS) {

                    /* If device is already known, do not read any char except CCID status */
                    if(scardReaderList.isCorrectlyKnown) {
                        uuidCharacteristicsToRead.clear()
                        uuidCharacteristicsToRead.add(GattAttributesSpringCore.UUID_CCID_STATUS_CHAR)
                    }

                    val services =  lowLayer.getServices()
                    Log.d(TAG, services.toString())

                    if(services.isEmpty()) {
                        postReaderListError(SCardError.ErrorCodes.MISSING_SERVICE, "Android thinks that the GATT of the device is empty")
                        return
                    }

                    for (srv in services) {
                        Log.d(TAG, "Service = " + srv.uuid.toString())
                        for (chr in srv.characteristics) {
                            Log.d(TAG, "    Characteristic = ${chr.uuid}")

                            if(uuidCharacteristicsCanIndicate.contains(chr.uuid)) {
                                characteristicsCanIndicate.add(chr)
                            }
                            if(uuidCharacteristicsToRead.contains(chr.uuid)){
                                characteristicsToRead.add(chr)
                            }
                            if(uuidCharacteristicsToReadPower.contains(chr.uuid)) {
                                characteristicsToReadPower.add(chr)
                            }
                            if(GattAttributesSpringCore.UUID_CCID_PC_TO_RDR_CHAR == chr.uuid) {
                               charCcidPcToRdr = chr
                            }
                        }
                    }

                    if(uuidCharacteristicsCanIndicate.size != characteristicsCanIndicate.size
                        || uuidCharacteristicsToRead.size != characteristicsToRead.size
                        || uuidCharacteristicsToReadPower.size != characteristicsToReadPower.size) {
                        postReaderListError(SCardError.ErrorCodes.MISSING_CHARACTERISTIC, "One or more characteristic are missing in the GATT")
                        return
                    }

                    Log.d(TAG, "Go to ReadingInformation")
                    currentState = State.ReadingInformation
                    /* trigger 1st read */
                    val chr = characteristicsToRead[0]
                    lowLayer.readCharacteristic(chr)

                } else {
                    Log.w(TAG, "onServicesDiscovered received: ${event.status}")
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private var indexCharToBeRead: Int = 0
    private fun handleStateReadingInformation(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicRead -> {
                if(event.status != BluetoothGatt.GATT_SUCCESS) {
                    postReaderListError(SCardError.ErrorCodes.READ_CHARACTERISTIC_FAILED, "Failed to subscribe to read characteristic ${event.characteristic}")
                    return
                }

                when(event.characteristic.uuid) {
                    GattAttributesSpringCore.UUID_MODEL_NUMBER_STRING_CHAR -> scardReaderList.productName = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_SERIAL_NUMBER_STRING_CHAR -> {
                        scardReaderList.serialNumber = event.characteristic.value.toString(charset("ASCII"))
                        scardReaderList.serialNumberRaw = event.characteristic.value.toString(charset("ASCII")).hexStringToByteArray()
                    }
                    GattAttributesSpringCore.UUID_FIRMWARE_REVISION_STRING_CHAR -> scardReaderList.softwareVersion = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_HARDWARE_REVISION_STRING_CHAR -> scardReaderList.hardwareVersion = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_SOFTWARE_REVISION_STRING_CHAR -> getVersionFromRevString(event.characteristic.value.toString(charset("ASCII")))
                    GattAttributesSpringCore.UUID_MANUFACTURER_NAME_STRING_CHAR -> scardReaderList.vendorName = event.characteristic.value.toString(charset("ASCII"))
                    GattAttributesSpringCore.UUID_PNP_ID_CHAR -> scardReaderList.pnpId = event.characteristic.value.toHexString()
                    GattAttributesSpringCore.UUID_CCID_STATUS_CHAR -> {
                        val slotCount = event.characteristic.value[0] and LOW_POWER_NOTIFICATION.inv()

                        if(slotCount.toInt() == 0) {
                            postReaderListError(SCardError.ErrorCodes.DUMMY_DEVICE, "This device has 0 slots")
                            return
                        }

                        /* Add n new readers */
                        if(!scardReaderList.isCorrectlyKnown) {
                            for (i in 0 until slotCount) {
                                scardReaderList.readers.add(SCardReader(scardReaderList))
                            }
                        }

                        /* Recreate dummy data with just slotCount and card absent on all slots */
                        val ccidStatusData = ByteArray(event.characteristic.value.size)
                        ccidStatusData[0] = event.characteristic.value[0]
                        for (i in 1 until event.characteristic.value.size) {
                            ccidStatusData[i] = 0x00
                        }

                        /* Update readers status */
                        interpretSlotsStatus(event.characteristic.value)

                        /* Check if there is some card already present and not connected on the slots */
                        listReadersToConnect.clear()
                        for (slot in scardReaderList.readers) {
                            if(slot.cardPresent and !slot.cardConnected) {
                                Log.d(TAG, "Slot: ${slot.name}, card present and not connected --> must connect to this card")
                                listReadersToConnect.add(slot)
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unhandled characteristic read : ${event.characteristic.uuid}")
                    }
                }

                indexCharToBeRead++
                if (indexCharToBeRead < characteristicsToRead.size) {
                    val chr = characteristicsToRead[indexCharToBeRead]
                    lowLayer.readCharacteristic(chr)
                }
                else {
                    Log.d(TAG, "Reading Information finished")
                    currentState = State.SubscribingNotifications
                    // Trigger 1st subscribing
                    val chr = characteristicsCanIndicate[0]
                    lowLayer.enableNotifications(chr)
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private var indexCharToBeSubscribed: Int = 0
    private fun handleStateSubscribingNotifications(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventDescriptorWrite -> {
                if(event.status != BluetoothGatt.GATT_SUCCESS) {
                    postReaderListError(SCardError.ErrorCodes.ENABLE_CHARACTERISTIC_EVENTS_FAILED, "Failed to subscribe to notification for characteristic ${event.descriptor.characteristic}")
                    return
                }

                indexCharToBeSubscribed++
                if (indexCharToBeSubscribed < characteristicsCanIndicate.size) {
                    val chr = characteristicsCanIndicate[indexCharToBeSubscribed]
                    lowLayer.enableNotifications(chr)
                }
                else {
                    Log.d(TAG, "Subscribing finished")

                    if(scardReaderList.isCorrectlyKnown) {
                        Log.d(TAG, "Device already known: go to processNextSlotConnection or authenticate")
                        /* Go to authenticate state if necessary */
                        if(scardReaderList.ccidHandler.isSecure) {
                            currentState = State.Authenticate
                            process(ActionEvent.ActionAuthenticate())
                        }
                        else {
                            /* If there are one card present on one or more slot --> go to state ConnectingToCard */
                            processNextSlotConnection()
                        }
                    }
                    else {
                        Log.d(TAG, "Device unknown: go to ReadingSlotsName")
                        currentState = State.ReadingSlotsName
                        /* Trigger 1st APDU to get slot name */
                        lowLayer.ccidWriteChar(scardReaderList.ccidHandler.scardControl("582100".hexStringToByteArray()))
                    }
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private fun handleStateReadingSlotsName(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicChanged -> {
                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR)
                {
                    /* Response */
                    val ccidResponse = scardReaderList.ccidHandler.getCcidResponse(event.characteristic.value)
                    val slotName = ccidResponse.payload.slice(1 until ccidResponse.payload.size).toByteArray().toString(charset("ASCII"))
                    Log.d(TAG, "Slot $indexSlots name : $slotName")
                    scardReaderList.readers[indexSlots].name = slotName
                    scardReaderList.readers[indexSlots].index = indexSlots

                    /* Get next slot name */
                    indexSlots++
                    if (indexSlots < scardReaderList.readers.size) {
                        lowLayer.ccidWriteChar(scardReaderList.ccidHandler.scardControl("58210$indexSlots".hexStringToByteArray()))
                    }
                    else {
                        Log.d(TAG, "Reading readers name finished")

                        /* Go to authenticate state if necessary */
                        if(scardReaderList.ccidHandler.isSecure) {
                            currentState = State.Authenticate
                            process(ActionEvent.ActionAuthenticate())
                        }
                        else {
                            /* If there are one card present on one or more slot --> go to state ConnectingToCard */
                            processNextSlotConnection()
                        }
                    }
                }
                else {
                    handleCommonActionEvents(event)
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private var authenticateStep = 0
    private fun handleStateAuthenticate(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.ActionAuthenticate -> {
                lowLayer.ccidWriteChar(scardReaderList.ccidHandler.scardControl(scardReaderList.ccidHandler.ccidSecure.hostAuthCmd()))
                authenticateStep = 1
            }
            is ActionEvent.EventCharacteristicChanged -> {
                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR)
                {
                    val ccidResponse = scardReaderList.ccidHandler.getCcidResponse(event.characteristic.value)
                    if(authenticateStep == 1) {

                        scardReaderList.ccidHandler.ccidSecure.deviceRespStep1(ccidResponse.payload)
                        lowLayer.ccidWriteChar(
                            scardReaderList.ccidHandler.scardControl(
                                scardReaderList.ccidHandler.ccidSecure.hostCmdStep2(ccidResponse.payload.toMutableList())
                            )
                        )
                        authenticateStep = 2
                    }
                    else if(authenticateStep == 2) {
                        scardReaderList.ccidHandler.ccidSecure.deviceRespStep3(ccidResponse.payload)

                        scardReaderList.ccidHandler.authenticateOk = true
                        processNextSlotConnection()
                    }
                }
                else {
                    handleCommonActionEvents(event)
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private var rxBuffer = mutableListOf<Byte>()
    private fun handleStateConnectingToCard(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.ActionWriting -> {

                /* Clear and set data to write */
                lowLayer.putDataToBeWrittenSequenced(event.command.toList())

                /* Trigger 1st write operation */
                if(lowLayer.ccidWriteCharSequenced()) {
                    Log.d(TAG, "Write finished")
                    currentState = State.WaitingResponse
                }
            }
            is ActionEvent.EventCharacteristicWrite -> {
                Log.d(TAG, "Write succeed")
            }
            is ActionEvent.EventCharacteristicChanged -> {

                if (event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR) {

                    rxBuffer.addAll(event.characteristic.value.toList())
                    val ccidLength = scardReaderList.ccidHandler.getCcidLength(rxBuffer.toByteArray())

                    /* Check if the response is compete or not */
                    if( rxBuffer.size- CcidFrame.HEADER_SIZE != ccidLength) {
                        Log.d(TAG, "Frame not complete, excepted length = $ccidLength")
                    }
                    else {
                        /* Put data in ccid frame */
                        val ccidResponse = scardReaderList.ccidHandler.getCcidResponse(rxBuffer.toByteArray())
                        val slot = scardReaderList.readers[ccidResponse.slotNumber.toInt()]

                        /* Update slot status (present, powered) */
                        interpretSlotsStatusInCcidHeader(
                            ccidResponse.slotStatus,
                            slot
                        )

                        /* Check slot error */
                        if (!interpretSlotsErrorInCcidHeader(
                                ccidResponse.slotError,
                                ccidResponse.slotStatus,
                                slot
                            )
                        ) {
                            Log.d(TAG, "Error, do not process CCID packet, returning to Idle state")
                            /* reset rxBuffer */
                            rxBuffer = mutableListOf<Byte>()

                            /* Remove reader we just processed */
                            listReadersToConnect.remove(slot)
                            processNextSlotConnection()

                            /* Do not go further */
                            return
                        }

                        Log.d(TAG, "Frame complete, length = ${ccidResponse.length}")

                        /* reset rxBuffer */
                        rxBuffer = mutableListOf<Byte>()
                        if (ccidResponse.code == CcidResponse.ResponseCode.RDR_To_PC_DataBlock.value) {

                            // save ATR
                            slot.channel.atr = ccidResponse.payload
                            // set cardConnected flag
                            slot.cardConnected = true

                            /* Remove reader we just processed */
                            listReadersToConnect.remove(slot)

                            scardReaderList.postCallback({
                                callbacks.onReaderStatus(
                                    slot,
                                    slot.cardPresent,
                                    slot.cardConnected
                                )
                            })

                            /* Change state if we are at the end of the list */
                            processNextSlotConnection()
                        }
                    }
                }
                else {
                    handleCommonActionEvents(event)
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private fun handleStateIdle(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.ActionWriting -> {
                currentState = State.WritingCommand

                /* Clear and set data to write */
                lowLayer.putDataToBeWrittenSequenced(event.command.toList())

                /* Trigger 1st write operation */
                if(lowLayer.ccidWriteCharSequenced()) {
                    Log.d(TAG, "Write finished")
                    currentState = State.WaitingResponse
                }
            }
            is ActionEvent.ActionReadPowerInfo -> {
                currentState = State.ReadingPowerInfo
                process(event)
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private var indexCharToReadPower = 0
    private var batteryLevel = 0
    private var powerState = 0
    private fun handleStateReadingPowerInfo(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicRead -> {

                when(event.characteristic.uuid) {
                    GattAttributesSpringCore.UUID_BATTERY_LEVEL_CHAR -> batteryLevel = event.characteristic.value[0].toInt()
                    GattAttributesSpringCore.UUID_BATTERY_POWER_STATE_CHAR -> {
                        /* cf https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.characteristic.battery_power_state.xml*/
                        /* Check Charging (Chargeable) state */
                        val charging = 0b00110000.toByte()
                        powerState = if(event.characteristic.value[0] and charging == charging) {
                            1
                        } else {
                            2
                        }
                    }
                }

                /* Read next */
                indexCharToReadPower++
                if(indexCharToReadPower<characteristicsToReadPower.size) {
                    val chr = characteristicsToReadPower[indexCharToReadPower]
                    lowLayer.readCharacteristic(chr)
                }
                else {
                    currentState = State.Idle
                    /* read done --> send callback */
                    scardReaderList.postCallback({
                        callbacks.onPowerInfo(
                            scardReaderList,
                            powerState,
                            batteryLevel
                        )
                    })
                    processNextSlotConnection()
                }
            }
            is ActionEvent.ActionReadPowerInfo -> {
                /* Trigger 1st read */
                indexCharToReadPower = 0
                val chr = characteristicsToReadPower[indexCharToReadPower]
                lowLayer.readCharacteristic(chr)
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private fun handleStateWritingCommand(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicWrite -> {

                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_PC_TO_RDR_CHAR) {
                    if(event.status == BluetoothGatt.GATT_SUCCESS) {
                        if(lowLayer.ccidWriteCharSequenced()) {
                            Log.d(TAG, "Write finished")
                            currentState = State.WaitingResponse
                        }
                    }
                    else {
                        currentState = State.Idle
                        postReaderListError(SCardError.ErrorCodes.WRITE_CHARACTERISTIC_FAILED,"Writing on characteristic ${event.characteristic.uuid} failed with status ${event.status} (BluetoothGatt constant)")
                    }
                }
                else {
                    Log.w(TAG,"Received written indication on an unexpected characteristic  ${event.characteristic.uuid}")
                }
            }
            /* If reader answer us before we have the write ok event */
            is ActionEvent.EventCharacteristicChanged -> {

                if (event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR) {

                    /* Verify that write has finished */
                    if (lowLayer.ccidWriteCharSequenced()) {
                        Log.d(TAG, "Write finished")
                        currentState = State.WaitingResponse


                        rxBuffer.addAll(event.characteristic.value.toList())
                        val ccidLength = scardReaderList.ccidHandler.getCcidLength(rxBuffer.toByteArray())

                        /* Check if the response is compete or not */
                        if (rxBuffer.size - CcidFrame.HEADER_SIZE != ccidLength) {
                            Log.d(TAG, "Frame not complete, excepted length = $ccidLength")
                        } else {
                            analyseResponse(rxBuffer.toByteArray())

                            /* reset rxBuffer */
                            rxBuffer = mutableListOf<Byte>()

                            /* Check if there are some cards to connect*/
                            processNextSlotConnection()
                        }
                    }
                }
                else {
                    handleCommonActionEvents(event)
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private fun handleStateWaitingResponse(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventCharacteristicChanged -> {

                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_RDR_TO_PC_CHAR) {

                    rxBuffer.addAll(event.characteristic.value.toList())
                    val ccidLength = scardReaderList.ccidHandler.getCcidLength(rxBuffer.toByteArray())

                    /* Check if the response is compete or not */
                    if( rxBuffer.size- CcidFrame.HEADER_SIZE != ccidLength) {
                        Log.d(TAG, "Frame not complete, excepted length = $ccidLength")
                    }
                    else {
                        analyseResponse(rxBuffer.toByteArray())

                        /* reset rxBuffer */
                        rxBuffer = mutableListOf<Byte>()

                        /* Check if there are some cards to connect */
                        processNextSlotConnection()
                    }
                }
                else {
                    handleCommonActionEvents(event)
                }
            }
            else -> handleCommonActionEvents(event)
        }
    }

    private fun handleStateDisconnecting(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName}")
        when (event) {
            is ActionEvent.EventDisconnected -> {
                scardReaderList.isConnected = false
                currentState = State.Disconnected
                scardReaderList.postCallback({ callbacks.onReaderListClosed(scardReaderList) })
                scardReaderList.isAlreadyCreated = false

                // Reset all lists
                indexCharToBeSubscribed = 0
                indexCharToBeRead = 0
                indexSlots = 0

                lowLayer.close()
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }

    private fun handleCommonActionEvents(event: ActionEvent) {
        Log.d(TAG, "ActionEvent ${event.javaClass.simpleName} (Common)")
        when (event) {
            is ActionEvent.ActionDisconnect -> {
                currentState = State.Disconnecting
                lowLayer.disconnect()
            }
            is ActionEvent.EventDisconnected -> {
                currentState = State.Disconnected
                scardReaderList.isConnected = false
                scardReaderList.postCallback({ callbacks.onReaderListClosed(scardReaderList) })
                scardReaderList.isAlreadyCreated = false

                // Reset all lists
                indexCharToBeSubscribed = 0
                indexCharToBeRead = 0
                indexSlots = 0

                lowLayer.close()
            }
            is ActionEvent.EventCharacteristicChanged -> {
                if(event.characteristic.uuid == GattAttributesSpringCore.UUID_CCID_STATUS_CHAR) {
                    /* Update readers status */
                    interpretSlotsStatus(event.characteristic.value)

                    /* Update list of slots to connect */
                    for (slot in scardReaderList.readers) {
                        if (!slot.cardPresent && listReadersToConnect.contains(slot)) {
                            Log.d(TAG, "Card gone on slot ${slot.index}, removing slot from listReadersToConnect")
                            listReadersToConnect.remove(slot)
                        } else if (slot.cardPresent && !slot.cardConnected && !listReadersToConnect.contains(slot)) {
                            Log.d(TAG, "Card arrived on slot ${slot.index}, adding slot to listReadersToConnect")
                            listReadersToConnect.add(slot)
                        }
                    }

                    /* If we are idle or already connecting to cards */
                    if(currentState == State.Idle || currentState == State.ConnectingToCard) {
                        processNextSlotConnection()
                    }
                }
                else {
                    Log.w(TAG,"Received notification/indication on an unexpected characteristic ${event.characteristic.uuid} (value: ${event.characteristic.value.toHexString()})")
                }
            }
            else -> Log.w(TAG, "Unwanted ActionEvent ${event.javaClass.simpleName}")
        }
    }
}