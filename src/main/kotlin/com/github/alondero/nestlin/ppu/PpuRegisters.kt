package com.github.alondero.nestlin.ppu

data class PpuRegisters (
        var controller: Byte = 0,
        var mask: Byte = 0,
        var status: Byte = 0,
        var oamAddress: Byte = 0,
        var oamData: Byte = 0,
        var scroll: Byte = 0,
        var address: Byte = 0,
        var data: Byte = 0
) {
    fun reset() {
        controller = 0
        mask = 0
        status = 0
        oamAddress = 0
        oamData = 0
        scroll = 0
        address = 0
        data = 0
    }

    operator fun get(addr: Int): Byte {
        when (addr) {
            0 -> return controller
            1 -> return mask
            2 -> return status
            3 -> return oamAddress
            4 -> return oamData
            5 -> return scroll
            6 -> return address
            else /*7*/ -> return data
        }
    }

    operator fun set(addr: Int, value: Byte) {
        when (addr) {
            0 -> controller = value
            1 -> mask = value
            2 -> status = value
            3 -> oamAddress = value
            4 -> oamData = value
            5 -> scroll = value
            6 -> address = value
            else /*7*/ -> data = value
        }
    }
}