package fm2

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InputReplayerTest {
    @Test
    fun testReplayerStartsWithFirstFrame() {
        val frames = listOf(
            InputFrame(port0 = ButtonState.fromString(".......A"), port1 = ButtonState()),
            InputFrame(port0 = ButtonState.fromString("........"), port1 = ButtonState())
        )
        val replayer = InputReplayer(frames)

        val frame = replayer.nextFrame()
        assertEquals(true, frame?.port0?.a)
    }

    @Test
    fun testReplayerAdvancesFrames() {
        val frames = listOf(
            InputFrame(port0 = ButtonState.fromString(".......A"), port1 = ButtonState()),
            InputFrame(port0 = ButtonState.fromString("......B."), port1 = ButtonState()),
            InputFrame(port0 = ButtonState.fromString("........"), port1 = ButtonState())
        )
        val replayer = InputReplayer(frames)

        replayer.nextFrame() // Frame 0
        val frame1 = replayer.nextFrame() // Frame 1
        assertEquals(true, frame1?.port0?.b)
    }

    @Test
    fun testReplayerReturnsNullAtEnd() {
        val frames = listOf(
            InputFrame(port0 = ButtonState.fromString(".......A"), port1 = ButtonState())
        )
        val replayer = InputReplayer(frames)

        replayer.nextFrame()
        val endFrame = replayer.nextFrame()
        assertNull(endFrame)
    }

    @Test
    fun testReplayerHandlesSoftReset() {
        val frames = listOf(
            InputFrame(commands = 1, port0 = ButtonState(), port1 = ButtonState()) // Soft reset
        )
        val replayer = InputReplayer(frames)

        val frame = replayer.nextFrame()
        assertEquals(1, frame!!.commands)
    }

    @Test
    fun testGetCurrentFrameNumber() {
        val frames = List(5) { InputFrame() }
        val replayer = InputReplayer(frames)

        assertEquals(0, replayer.currentFrameNumber)
        replayer.nextFrame()
        assertEquals(1, replayer.currentFrameNumber)
    }
}
