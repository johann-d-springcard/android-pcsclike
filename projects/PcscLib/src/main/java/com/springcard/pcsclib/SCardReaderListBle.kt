/**
 * Copyright (c) 2018-2019 SpringCard - www.springcard.com
 * All right reserved
 * This software is covered by the SpringCard SDK License Agreement - see LICENSE.txt
 */

package com.springcard.pcsclib

import android.bluetooth.BluetoothDevice
import android.content.Context

class SCardReaderListBle(layerDevice: BluetoothDevice, callbacks: SCardReaderListCallback): SCardReaderList(layerDevice as Any, callbacks) {

    override fun connect(ctx : Context) {
        if(layerDevice is BluetoothDevice) {
            commLayer = BluetoothLayer(layerDevice, callbacks, this)
            process(ActionEvent.ActionConnect(ctx))
        }
    }
}