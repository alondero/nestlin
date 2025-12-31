package fm2

class InputReplayer(val frames: List<InputFrame>) {
    private var currentIndex = 0

    val currentFrameNumber: Int
        get() = currentIndex

    fun nextFrame(): InputFrame? {
        if (currentIndex >= frames.size) {
            return null
        }
        return frames[currentIndex++]
    }

    fun reset() {
        currentIndex = 0
    }

    fun getTotalFrames(): Int = frames.size

    fun isFinished(): Boolean = currentIndex >= frames.size
}
