fun main() {
    class VramAddress {
        var coarseXScroll = 0
        var coarseYScroll = 0
        var horizontalNameTable = false
        var verticalNameTable = false
        var fineYScroll = 0

        private fun getNameTable() = (if (verticalNameTable) 2 else 0) + (if (horizontalNameTable) 1 else 0)
        fun asAddress() = (((((fineYScroll shl 2) or getNameTable()) shl 5) or coarseYScroll) shl 5) or coarseXScroll
    }
    
    val addr = VramAddress()
    addr.fineYScroll = 0
    addr.horizontalNameTable = false
    addr.verticalNameTable = false
    addr.coarseYScroll = 0
    addr.coarseXScroll = 31
    
    println("asAddress result: 0x${addr.asAddress().toString(16).padStart(4, '0')}")
    println("Expected: 0x001f")
}
