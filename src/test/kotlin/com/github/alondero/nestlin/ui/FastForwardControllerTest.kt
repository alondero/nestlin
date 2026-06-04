package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.EmulatorConfig
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

class FastForwardControllerTest {

    @Test
    fun `engage disables throttling and marks active`() {
        val config = EmulatorConfig(speedThrottlingEnabled = true)
        val ff = FastForwardController(config)

        ff.engage()

        assertThat(config.speedThrottlingEnabled, equalTo(false))
        assertThat(ff.active, equalTo(true))
    }

    @Test
    fun `release restores the prior throttle setting`() {
        val config = EmulatorConfig(speedThrottlingEnabled = true)
        val ff = FastForwardController(config)

        ff.engage()
        ff.release()

        assertThat(config.speedThrottlingEnabled, equalTo(true))
        assertThat(ff.active, equalTo(false))
    }

    @Test
    fun `release restores throttling-disabled setting when fast-forward was engaged over it`() {
        // User had throttling already off (e.g. via Ctrl+T); fast-forward must not silently re-enable it.
        val config = EmulatorConfig(speedThrottlingEnabled = false)
        val ff = FastForwardController(config)

        ff.engage()
        assertThat(config.speedThrottlingEnabled, equalTo(false))

        ff.release()
        assertThat(config.speedThrottlingEnabled, equalTo(false))
        assertThat(ff.active, equalTo(false))
    }

    @Test
    fun `repeated engage calls do not clobber the saved setting`() {
        // Key auto-repeat fires KEY_PRESSED many times while held; only the first should capture state.
        val config = EmulatorConfig(speedThrottlingEnabled = true)
        val ff = FastForwardController(config)

        ff.engage()
        // A stray toggle while held shouldn't be captured as the "prior" setting.
        config.speedThrottlingEnabled = false
        ff.engage()
        ff.release()

        assertThat(config.speedThrottlingEnabled, equalTo(true))
    }

    @Test
    fun `release without engage is a no-op`() {
        val config = EmulatorConfig(speedThrottlingEnabled = true)
        val ff = FastForwardController(config)

        ff.release()

        assertThat(config.speedThrottlingEnabled, equalTo(true))
        assertThat(ff.active, equalTo(false))
    }
}
