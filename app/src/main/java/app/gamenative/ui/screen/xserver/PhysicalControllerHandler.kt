package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.GamepadState
import com.winlator.inputcontrols.JoyConSupport
import com.winlator.math.Mathf
import com.winlator.xserver.XServer
import java.util.Timer
import java.util.TimerTask
import timber.log.Timber

/**
 * Standalone handler for physical controller input that works independently of view visibility.
 * Applies profile bindings to convert physical controller input into virtual gamepad state.
 *
 * Accumulate + fixed-tick flush: onGenericMotionEvent/onKeyEvent only ever update in-memory
 * controller state (cheap, unthrottled). [flushTimer] ticks at [effectiveHz] and [flushTick] is
 * the only place that diffs that state and dispatches to XServer/WinHandler, at most once per
 * tick. Digital button KeyEvents are already discrete edges, so they skip the accumulator and
 * dispatch immediately via [sendGamepadState].
 */
class PhysicalControllerHandler(
    private var profile: ControlsProfile?,
    private val xServer: XServer?,
    private val onOpenNavigationMenu: (() -> Unit)? = null,
    private val onShowKeyboard: (() -> Unit)? = null,
    private val onRadialMenuButtonStateChanged: ((Boolean, Boolean) -> Unit)? = null,
    private val onRadialMenuVectorChanged: ((Float, Float) -> Unit)? = null,
    private val onGyroModifierChanged: ((Any, Boolean) -> Unit)? = null,
    private val gyroStickMixer: ((Binding, Boolean, Float, Int) -> Float)? = null,
    private val gamepadStateSender: (GamepadState?) -> Unit = { state ->
        xServer?.winHandler?.let { winHandler ->
            winHandler.sendGamepadState()
            winHandler.sendVirtualGamepadState(state)
        }
    },
) {
    private data class PhysicalInputSource(val deviceId: Int, val keyCode: Int)

    private data class MouseMoveSource(
        val deviceId: Int,
        val keyCode: Int,
        val binding: Binding,
    )

    private data class BindingCacheEntry(val bindings: Map<Int, ExternalControllerBinding>, val sourceCount: Int)

    companion object {
        private const val SCROLL_REPEAT_INTERVAL_MS = 90L
        private const val UNKNOWN_DEVICE_ID = -1
        private const val SEQUENCE_PRESS_MS = 80L
        private const val STICK_RELEASE_THRESHOLD = 0.10f

        // Squared-distance noise gate for the stick movement anchor; see applyStickMovementGate().
        private const val STICK_MOVEMENT_EPSILON_SQR = 0.0025f

        /** Tick rate used when the user disables throttling from the quick menu. */
        private const val UNTHROTTLED_HZ = 120
        private const val DEFAULT_HZ = 60
        private const val MIN_HZ = 15
        private const val MAX_HZ = 240

        // Squared-magnitude noise floor for mouse-look; see flushMouseMove().
        private const val MOUSE_LOOK_MIN_MAGNITUDE_SQ = 0.05f * 0.05f
        // px/second equivalent of the old "* 10f per tick @ 60Hz" constant; see flushMouseMove().
        private const val MOUSE_LOOK_PX_PER_SECOND = 600f
    }

    private val TAG = "gncontrol"
    private val mouseMoveOffset = PointF(0f, 0f)
    private val mouseMoveRemainder = PointF(0f, 0f)
    private val mouseMoveContributions = mutableMapOf<MouseMoveSource, Float>()
    private val sequenceHandler = Handler(Looper.getMainLooper())
    private var scrollRepeatTimer: Timer? = null
    private val scrollRepeatLock = Any()
    private val activeScrollBindings = mutableSetOf<Binding>()

    private val joystickAxes = intArrayOf(
        MotionEvent.AXIS_X,
        MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z,
        MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_HAT_X,
        MotionEvent.AXIS_HAT_Y,
    )
    private val joystickValues = FloatArray(joystickAxes.size)

    // Per-device last-acted-upon anchors for the stick movement gate.
    private val leftStickAnchors = mutableMapOf<Int, PointF>()
    private val rightStickAnchors = mutableMapOf<Int, PointF>()

    // keyCode -> binding cache per device; getControllerBinding() is a linear scan and
    // processJoystickInput() calls this ~30x/tick. Invalidated when the binding count changes.
    private val bindingCache = mutableMapOf<Int, BindingCacheEntry>()

    // Devices that delivered at least one MotionEvent; flushTick() is the only reader.
    private val trackedDeviceIds = mutableSetOf<Int>()
    private var flushTimer: Timer? = null
    private var flushIntervalMs = 1000L / DEFAULT_HZ
    private var configuredHz = DEFAULT_HZ
    private var throttlingEnabled = true
    private val flushLock = Any()

    // Last GamepadState actually sent; see gamepadStateChanged().
    private val lastSentGamepadState = GamepadState()
    private var hasSentGamepadStateOnce = false

    private var lastMouseFlushNanos = 0L

    // track which axis keycodes are currently "pressed" so we only release on actual transitions.
    // Main-thread only: flushTick() runs via sequenceHandler.post, never the raw Timer thread.
    private val activeAxisBindings = mutableSetOf<PhysicalInputSource>()
    private val activeButtonBindings = mutableMapOf<PhysicalInputSource, BindingCombo>()
    private val activeTriggerBindings = mutableMapOf<PhysicalInputSource, BindingCombo>()
    private val activeSequenceTriggerBindings = mutableSetOf<PhysicalInputSource>()
    private val activeSequenceBindings = mutableMapOf<Binding, Int>()
    private val activeSequenceGyroSources = mutableSetOf<PhysicalInputSource>()
    private val activeGyroModifierSources = mutableSetOf<PhysicalInputSource>()

    // Tracks whether SHOW_KEYBOARD is currently held, so onShowKeyboard fires once per press (rising edge only)
    private var showKeyboardPressed = false
    private var radialMenuPressed = false
    private var radialMenuOpenedFromMotion = false
    private var radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID

    init {
        if (profile != null) startFlushTimerLocked()
    }

    private fun cachedBinding(controller: ExternalController, deviceId: Int, keyCode: Int): ExternalControllerBinding? {
        val count = controller.controllerBindingCount
        val cached = bindingCache[deviceId]
        val entry = if (cached == null || cached.sourceCount != count) {
            val map = HashMap<Int, ExternalControllerBinding>(count)
            for (i in 0 until count) {
                val binding = controller.getControllerBindingAt(i)
                map[binding.keyCodeForAxis] = binding
            }
            BindingCacheEntry(map, count).also { bindingCache[deviceId] = it }
        } else {
            cached
        }
        return entry.bindings[keyCode]
    }

    fun setInputPollRateHz(hz: Int) {
        configuredHz = hz.coerceIn(MIN_HZ, MAX_HZ)
        rescheduleFlushTimer()
    }

    fun setInputThrottlingEnabled(enabled: Boolean) {
        throttlingEnabled = enabled
        rescheduleFlushTimer()
    }

    private fun effectiveHz(): Int = if (throttlingEnabled) configuredHz else UNTHROTTLED_HZ

    private fun rescheduleFlushTimer() {
        val newIntervalMs = 1000L / effectiveHz()
        synchronized(flushLock) {
            if (newIntervalMs == flushIntervalMs && flushTimer != null) return
            flushIntervalMs = newIntervalMs
            flushTimer?.cancel()
            flushTimer = null
            startFlushTimerLocked()
        }
    }

    private fun startFlushTimerLocked() {
        if (flushTimer != null) return
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                // Hop to the main thread - flushTick() shares state with onKeyEvent. An
                // uncaught exception here would silently kill this Timer's thread forever
                // (java.util.Timer quirk), so catch and log instead of losing all future ticks.
                try {
                    sequenceHandler.post { flushTick() }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "flushTimer tick failed")
                }
            }
        }, 0, flushIntervalMs)
        flushTimer = timer
    }

    private fun stopFlushTimer() {
        synchronized(flushLock) {
            flushTimer?.cancel()
            flushTimer = null
        }
    }

    private fun releaseActiveAxes(
        exceptSource: PhysicalInputSource? = null,
        deviceId: Int? = null,
    ) {
        for (source in activeAxisBindings.toList()) {
            if (source == exceptSource || (deviceId != null && source.deviceId != deviceId)) continue
            activeAxisBindings.remove(source)
            val controller = profile?.getController(source.deviceId) ?: continue
            controller.getControllerBinding(source.keyCode)
                ?.takeIf { Binding.OPEN_RADIAL_MENU !in it.bindingCombo.bindings }
                ?.let {
                    handleInputEvent(
                        it.bindingCombo,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = source.keyCode,
                        sourceDeviceId = source.deviceId,
                        sourceController = controller,
                    )
                }
        }
    }

    private fun releaseActiveBindings(
        bindings: MutableMap<PhysicalInputSource, BindingCombo>,
        deviceId: Int? = null,
        fromMotion: Boolean = false,
    ) {
        for ((source, bindingCombo) in bindings.toMap()) {
            if (deviceId != null && source.deviceId != deviceId) continue
            bindings.remove(source)
            val controller = profile?.getController(source.deviceId)
            handleInputEvent(
                bindingCombo,
                false,
                0f,
                fromMotion = fromMotion,
                sourceKeyCode = source.keyCode,
                sourceDeviceId = source.deviceId,
                sourceController = controller,
            )
        }
    }

    fun setProfile(profile: ControlsProfile?) {
        releaseActiveBindings(activeButtonBindings)
        releaseActiveBindings(activeTriggerBindings, fromMotion = true)
        releaseGyroModifierSources()
        releaseActiveAxes()
        cancelActiveSequences()
        clearMouseMoveContributions()
        clearScrollRepeats()
        closeRadialMenuIfOpen(commit = false)
        activeSequenceTriggerBindings.clear()
        trackedDeviceIds.clear()
        sendGamepadState()
        this.profile = profile
        if (profile != null) {
            synchronized(flushLock) { startFlushTimerLocked() }
        } else {
            stopFlushTimer()
        }
        Timber.tag(TAG).d("PhysicalControllerHandler: Profile set to ${profile?.name}")
    }

    /**
     * Clean up resources when handler is destroyed
     */
    fun cleanup() {
        stopFlushTimer()
        releaseActiveBindings(activeButtonBindings)
        releaseActiveBindings(activeTriggerBindings, fromMotion = true)
        releaseGyroModifierSources()
        releaseActiveAxes()
        cancelActiveSequences()
        clearMouseMoveContributions()
        clearScrollRepeats()
        activeSequenceTriggerBindings.clear()
        trackedDeviceIds.clear()
        showKeyboardPressed = false
        closeRadialMenuIfOpen(commit = false)
        sendGamepadState()
    }

    fun onInputDeviceRemoved(deviceId: Int) {
        trackedDeviceIds.remove(deviceId)
        cancelActiveSequences()
        releaseActiveBindings(activeButtonBindings, deviceId)
        releaseActiveBindings(activeTriggerBindings, deviceId, fromMotion = true)
        releaseActiveAxes(deviceId = deviceId)
        releaseGyroModifierSources(deviceId)
        mouseMoveContributions.keys.removeAll { it.deviceId == deviceId }
        recalculateMouseMoveOffset()
        leftStickAnchors.remove(deviceId)
        rightStickAnchors.remove(deviceId)
        sendGamepadState()
        if (radialMenuPressed && radialMenuOpenerDeviceId == deviceId) {
            closeRadialMenuIfOpen(commit = false)
        }
    }

    /**
     * Handle physical controller button events.
     * Digital buttons are discrete Android KeyEvents, not a polled stream - there is nothing to
     * accumulate here, so these are still dispatched immediately, same as before.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (profile != null && event.repeatCount == 0) {
            val keyCode = JoyConSupport.remapKeyCode(event.device, event)
            if (radialMenuPressed && !isRadialMenuOpenerDevice(event.deviceId)) return true
            val controller = profile?.getController(event.deviceId)
            if (controller != null) {
                val controllerBinding = controller.getControllerBinding(keyCode)
                if (radialMenuPressed && controllerBinding?.bindingCombo?.bindings?.contains(Binding.OPEN_RADIAL_MENU) == true) {
                    if (keyCode == radialMenuOpenerKeyCode ||
                        radialMenuOpenerKeyCode == KeyEvent.KEYCODE_UNKNOWN
                    ) {
                        handleInputEvent(
                            controllerBinding.bindingCombo,
                            event.action == KeyEvent.ACTION_DOWN,
                            sourceKeyCode = keyCode,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                        return true
                    }
                    handleRadialMenuNavigationKey(event, keyCode)
                    return true
                }
                if (radialMenuPressed && handleRadialMenuNavigationKey(event, keyCode)) {
                    return true
                }

                if (controllerBinding != null) {
                    // Some controllers emit BOTH a digital KeyEvent for L2/R2 and an analog axis value in MotionEvent.
                    // If this physical key is mapped to a virtual trigger AND the device exposes trigger axes,
                    // ignore the KeyEvent to avoid an initial "full press" spike. MotionEvent will provide the analog value.
                    if ((keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) &&
                        controllerBinding.bindingCombo.bindings.any { it == Binding.GAMEPAD_BUTTON_L2 || it == Binding.GAMEPAD_BUTTON_R2 } &&
                        deviceHasTriggerAxis(event.device, keyCode)
                    ) {
                        return true
                    }
                    val isActionDown = event.action == KeyEvent.ACTION_DOWN
                    val source = PhysicalInputSource(event.deviceId, keyCode)
                    val bindingCombo = if (!controllerBinding.bindingCombo.isSequence &&
                        Binding.OPEN_RADIAL_MENU !in controllerBinding.bindingCombo.bindings
                    ) {
                        if (isActionDown) {
                            activeButtonBindings[source] = controllerBinding.bindingCombo
                            controllerBinding.bindingCombo
                        } else {
                            activeButtonBindings.remove(source) ?: controllerBinding.bindingCombo
                        }
                    } else {
                        controllerBinding.bindingCombo
                    }
                    val offset = if (isActionDown &&
                        bindingCombo.bindings.any { it == Binding.GAMEPAD_BUTTON_L2 || it == Binding.GAMEPAD_BUTTON_R2 }
                    ) 1f else 0f
                    handleInputEvent(
                        bindingCombo,
                        isActionDown,
                        offset,
                        sourceKeyCode = keyCode,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )

                    sendGamepadState()
                    return true
                }
            }
        }
        return false
    }

    private fun deviceHasTriggerAxis(device: InputDevice?, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 ->
                hasMotionRange(device, MotionEvent.AXIS_LTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_BRAKE)
            KeyEvent.KEYCODE_BUTTON_R2 ->
                hasMotionRange(device, MotionEvent.AXIS_RTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_GAS)
            else -> false
        }
    }

    private fun hasMotionRange(device: InputDevice?, axis: Int): Boolean {
        if (device == null) return false
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null ||
            device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null ||
            device.getMotionRange(axis) != null
    }

    /**
     * Accumulate-only: updates controller state and marks the device as tracked. Never calls into
     * XServer/WinHandler directly - only [flushTick] does that, once per tick.
     *
     * Extracted from InputControlsView.onGenericMotionEvent()
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val controller = profile?.getController(event.deviceId) ?: return false

        controller.updateStateFromMotionEvent(event)
        trackedDeviceIds.add(event.deviceId)

        // Always "handled" so no fallback handler double-processes this sample on its own channel.
        return true
    }

    private fun gamepadStateChanged(current: GamepadState): Boolean {
        if (!hasSentGamepadStateOnce) return true
        // Quantized to wire precision (16-bit stick / 8-bit trigger), not raw floats - avoids
        // false positives from ADC jitter on a held-still stick.
        return GamepadState.encodeThumbAxis(current.thumbLX) != GamepadState.encodeThumbAxis(lastSentGamepadState.thumbLX) ||
            GamepadState.encodeThumbAxis(current.thumbLY) != GamepadState.encodeThumbAxis(lastSentGamepadState.thumbLY) ||
            GamepadState.encodeThumbAxis(current.thumbRX) != GamepadState.encodeThumbAxis(lastSentGamepadState.thumbRX) ||
            GamepadState.encodeThumbAxis(current.thumbRY) != GamepadState.encodeThumbAxis(lastSentGamepadState.thumbRY) ||
            quantizeTrigger(current.triggerL) != quantizeTrigger(lastSentGamepadState.triggerL) ||
            quantizeTrigger(current.triggerR) != quantizeTrigger(lastSentGamepadState.triggerR) ||
            current.buttons != lastSentGamepadState.buttons ||
            !current.dpad.contentEquals(lastSentGamepadState.dpad)
    }

    private fun quantizeTrigger(value: Float): Int = (value * 255).toInt()

    /** Sends unconditionally - used for real transitions (button down/up, profile switch, cleanup). */
    private fun sendGamepadState() {
        profile?.gamepadState?.let { lastSentGamepadState.copy(it) }
        hasSentGamepadStateOnce = true
        gamepadStateSender(profile?.gamepadState)
    }

    /** Sends only if the state actually differs from what was last sent - used by the flush tick. */
    private fun sendGamepadStateIfChanged() {
        val state = profile?.gamepadState ?: return
        if (!gamepadStateChanged(state)) return
        sendGamepadState()
    }

    /**
     * Runs once per tick at [effectiveHz]: processes every tracked device, flushes mouse-look,
     * and sends gamepad state only if it changed since the last tick.
     */
    private fun flushTick() {
        flushTickBody()
    }

    private fun flushTickBody() {
        val currentProfile = profile ?: return
        if (radialMenuPressed) {
            // Only the opener device drives the radial menu while it's open; other devices'
            // axis state still gets refreshed so nothing "sticks" once it closes.
            for (deviceId in trackedDeviceIds.toList()) {
                if (!isRadialMenuOpenerDevice(deviceId)) continue
                val controller = currentProfile.getController(deviceId) ?: continue
                updateRadialMenuVector(controller)
                if (radialMenuOpenedFromMotion && !isRadialMenuMotionOpenerPressed(controller)) {
                    handleInputEvent(
                        Binding.OPEN_RADIAL_MENU,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = radialMenuOpenerKeyCode,
                        sourceDeviceId = deviceId,
                        sourceController = controller,
                    )
                }
            }
        } else {
            for (deviceId in trackedDeviceIds.toList()) {
                val controller = currentProfile.getController(deviceId) ?: continue
                processTriggers(controller, deviceId)
                if (radialMenuPressed) break // a trigger binding may have just opened the menu
                processJoystickInput(controller, deviceId)
                if (radialMenuPressed) break
            }
        }

        flushMouseMove()
        sendGamepadStateIfChanged()
    }

    private fun processTriggers(controller: ExternalController, deviceId: Int) {
        var controllerBinding = cachedBinding(controller, deviceId, KeyEvent.KEYCODE_BUTTON_L2)
        if (controllerBinding != null) {
            handleTriggerBinding(
                KeyEvent.KEYCODE_BUTTON_L2,
                controllerBinding.binding,
                controllerBinding.bindingCombo,
                controller.state.triggerL > 0f,
                controller.state.triggerL,
                fromMotion = true,
                sourceKeyCode = KeyEvent.KEYCODE_BUTTON_L2,
                sourceDeviceId = deviceId,
                sourceController = controller,
            )
            if (radialMenuPressed) return
        }

        controllerBinding = cachedBinding(controller, deviceId, KeyEvent.KEYCODE_BUTTON_R2)
        if (controllerBinding != null) {
            handleTriggerBinding(
                KeyEvent.KEYCODE_BUTTON_R2,
                controllerBinding.binding,
                controllerBinding.bindingCombo,
                controller.state.triggerR > 0f,
                controller.state.triggerR,
                fromMotion = true,
                sourceKeyCode = KeyEvent.KEYCODE_BUTTON_R2,
                sourceDeviceId = deviceId,
                sourceController = controller,
            )
        }
    }

    /** Flushes accumulated mouse-look movement once per tick, scaled by real elapsed time (dt). */
    private fun flushMouseMove() {
        val now = System.nanoTime()
        if (mouseMoveContributions.isEmpty()) {
            lastMouseFlushNanos = now
            return
        }

        val magnitudeSq = mouseMoveOffset.x * mouseMoveOffset.x + mouseMoveOffset.y * mouseMoveOffset.y
        if (magnitudeSq < MOUSE_LOOK_MIN_MAGNITUDE_SQ) {
            lastMouseFlushNanos = now
            return
        }

        // Clamp dt so a stalled/backgrounded tick (GC pause, app switch) can't produce one huge
        // jump once it resumes - cap at 100ms worth of movement.
        val dtSeconds = if (lastMouseFlushNanos == 0L) {
            flushIntervalMs / 1000f
        } else {
            ((now - lastMouseFlushNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        }
        lastMouseFlushNanos = now

        val cursorSpeed = profile?.cursorSpeed ?: 1f

        val rawDeltaX = mouseMoveOffset.x * MOUSE_LOOK_PX_PER_SECOND * cursorSpeed * dtSeconds + mouseMoveRemainder.x
        val rawDeltaY = mouseMoveOffset.y * MOUSE_LOOK_PX_PER_SECOND * cursorSpeed * dtSeconds + mouseMoveRemainder.y

        val moveX = rawDeltaX.toInt()
        val moveY = rawDeltaY.toInt()

        mouseMoveRemainder.x = rawDeltaX - moveX
        mouseMoveRemainder.y = rawDeltaY - moveY

        if (moveX != 0 || moveY != 0) {
            xServer?.injectPointerMoveDelta(moveX, moveY)
        }
    }

    private fun updateMouseMoveContribution(
        binding: Binding,
        isActionDown: Boolean,
        offset: Float,
        sourceKeyCode: Int,
        sourceDeviceId: Int,
    ) {
        if (isActionDown) {
            val contribution = if (offset != 0f) {
                offset
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_UP) {
                -1f
            } else {
                1f
            }
            mouseMoveContributions[MouseMoveSource(sourceDeviceId, sourceKeyCode, binding)] = contribution
        } else {
            mouseMoveContributions.keys.removeAll { source ->
                source.binding == binding &&
                    (sourceKeyCode == KeyEvent.KEYCODE_UNKNOWN || source.keyCode == sourceKeyCode) &&
                    (sourceDeviceId == UNKNOWN_DEVICE_ID || source.deviceId == sourceDeviceId)
            }
        }
        recalculateMouseMoveOffset()
    }

    private fun recalculateMouseMoveOffset() {
        mouseMoveOffset.set(0f, 0f)
        mouseMoveContributions.forEach { (source, contribution) ->
            if (source.binding == Binding.MOUSE_MOVE_LEFT || source.binding == Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x += contribution
            } else {
                mouseMoveOffset.y += contribution
            }
        }
    }

    private fun clearMouseMoveContributions() {
        mouseMoveContributions.clear()
        mouseMoveOffset.set(0f, 0f)
        mouseMoveRemainder.set(0f, 0f)
    }

    private fun handleScrollBinding(binding: Binding, isActionDown: Boolean): Boolean {
        if (binding != Binding.MOUSE_SCROLL_UP && binding != Binding.MOUSE_SCROLL_DOWN) {
            return false
        }

        var pulseImmediately = false
        synchronized(scrollRepeatLock) {
            if (isActionDown) {
                pulseImmediately = activeScrollBindings.add(binding)
                createScrollRepeatTimerLocked()
            } else {
                activeScrollBindings.remove(binding)
                if (activeScrollBindings.isEmpty()) {
                    cancelScrollRepeatTimerLocked()
                }
            }
        }

        if (pulseImmediately) {
            sendScrollPulse(binding)
        }
        return true
    }

    private fun createScrollRepeatTimerLocked() {
        if (scrollRepeatTimer != null) return
        scrollRepeatTimer = Timer()
        scrollRepeatTimer?.schedule(object : TimerTask() {
            override fun run() {
                // Same java.util.Timer caveat as startFlushTimerLocked(): an uncaught exception
                // here would silently and permanently kill scroll-repeat ticks.
                try {
                    val bindings = synchronized(scrollRepeatLock) {
                        activeScrollBindings.toList()
                    }
                    bindings.forEach { sendScrollPulse(it) }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "scrollRepeatTimer tick failed")
                }
            }
        }, SCROLL_REPEAT_INTERVAL_MS, SCROLL_REPEAT_INTERVAL_MS)
    }

    private fun cancelScrollRepeatTimerLocked() {
        scrollRepeatTimer?.cancel()
        scrollRepeatTimer = null
    }

    private fun clearScrollRepeats() {
        synchronized(scrollRepeatLock) {
            activeScrollBindings.clear()
            cancelScrollRepeatTimerLocked()
        }
    }

    private fun sendScrollPulse(binding: Binding) {
        val pointerButton = binding.pointerButton ?: return
        xServer?.injectPointerButtonPress(pointerButton)
        xServer?.injectPointerButtonRelease(pointerButton)
    }

    /**
     * Applies stick bindings once per tick per tracked device, diffing against
     * [activeAxisBindings] so digital (WASD-style) bindings only dispatch on real transitions.
     *
     * Extracted from InputControlsView.processJoystickInput()
     */
    private fun processJoystickInput(controller: ExternalController, deviceId: Int) {
        joystickValues[0] = controller.state.thumbLX
        joystickValues[1] = controller.state.thumbLY
        joystickValues[2] = controller.state.thumbRX
        joystickValues[3] = controller.state.thumbRY
        joystickValues[4] = controller.state.dPadX.toFloat()
        joystickValues[5] = controller.state.dPadY.toFloat()

        // Joint 2D noise gate for the sticks (dpad/hat stays ungated - already discrete). Gates
        // on total displacement from the last acted-upon position so a held diagonal can't trip
        // digital bindings on jitter; analog bindings keep tracking the live value.
        val leftGated = applyStickMovementGate(0, 1, leftStickAnchors.getOrPut(deviceId) { PointF(joystickValues[0], joystickValues[1]) }, controller, deviceId)
        val rightGated = applyStickMovementGate(2, 3, rightStickAnchors.getOrPut(deviceId) { PointF(joystickValues[2], joystickValues[3]) }, controller, deviceId)
        // dpad/hat (indices 4,5) is discrete hardware-side and never gated.
        val axisGated = booleanArrayOf(leftGated, leftGated, rightGated, rightGated, false, false)

        for (i in joystickAxes.indices) {
            // Frozen by the movement gate: unchanged since last tick, skip the lookup entirely.
            if (axisGated[i]) continue
            val axis = joystickAxes[i]
            val value = joystickValues[i]
            val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
            val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())
            val positiveSource = PhysicalInputSource(deviceId, posKeyCode)
            val negativeSource = PhysicalInputSource(deviceId, negKeyCode)

            val binding = cachedBinding(controller, deviceId, if (value > 0) posKeyCode else negKeyCode)
            val isAnalog = binding?.bindingCombo?.hasAnalog() == true
            val isDigital = !isAnalog && binding?.bindingCombo?.isSequence == false

            if (Math.abs(value) > ControlElement.STICK_DEAD_ZONE) {
                val activeKey = ExternalControllerBinding.getKeyCodeForAxis(axis, Mathf.sign(value))
                val oppositeKey = if (activeKey == posKeyCode) negKeyCode else posKeyCode
                val activeSource = if (activeKey == posKeyCode) positiveSource else negativeSource
                val oppositeSource = if (activeKey == posKeyCode) negativeSource else positiveSource

                val wasAlreadyActive = !activeAxisBindings.add(activeSource)

                cachedBinding(controller, deviceId, activeKey)?.let {
                    // Only inject a digital (WASD) key on its rising edge, not every tick.
                    if (isAnalog || !wasAlreadyActive || it.bindingCombo.isSequence) {
                        handleInputEvent(
                            it.bindingCombo,
                            true,
                            value,
                            fromMotion = true,
                            sourceKeyCode = activeKey,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
                if (radialMenuPressed) return

                // release opposite direction (if it was active)
                if (activeAxisBindings.remove(oppositeSource)) {
                    cachedBinding(controller, deviceId, oppositeKey)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = oppositeKey,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
            } else if (!isDigital || Math.abs(value) < STICK_RELEASE_THRESHOLD) {
                // For digital (WASD), only release if below STICK_RELEASE_THRESHOLD (Hysteresis)
                // For analog, release immediately when entering dead zone

                // release both directions only if they were active
                if (activeAxisBindings.remove(positiveSource)) {
                    cachedBinding(controller, deviceId, posKeyCode)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = posKeyCode,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
                if (activeAxisBindings.remove(negativeSource)) {
                    cachedBinding(controller, deviceId, negKeyCode)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = negKeyCode,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
            }
        }
    }

    /**
     * Freezes [joystickValues] at [anchor] while movement from it stays under
     * [STICK_MOVEMENT_EPSILON_SQR]; analog-bound axes are never gated.
     */
    private fun applyStickMovementGate(xIndex: Int, yIndex: Int, anchor: PointF, controller: ExternalController, deviceId: Int): Boolean {
        val x = joystickValues[xIndex]
        val y = joystickValues[yIndex]

        if (axisBindingIsAnalog(xIndex, x, controller, deviceId) || axisBindingIsAnalog(yIndex, y, controller, deviceId)) {
            anchor.set(x, y)
            return false
        }

        val dx = x - anchor.x
        val dy = y - anchor.y
        return if (dx * dx + dy * dy < STICK_MOVEMENT_EPSILON_SQR) {
            joystickValues[xIndex] = anchor.x
            joystickValues[yIndex] = anchor.y
            true
        } else {
            anchor.set(x, y)
            false
        }
    }

    private fun axisBindingIsAnalog(axisIndex: Int, value: Float, controller: ExternalController, deviceId: Int): Boolean {
        val axis = joystickAxes[axisIndex]
        val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
        val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())
        val binding = cachedBinding(controller, deviceId, if (value > 0) posKeyCode else negKeyCode)
        return binding?.bindingCombo?.hasAnalog() == true
    }

    private fun handleTriggerBinding(
        keyCode: Int,
        legacyBinding: Binding,
        bindingCombo: BindingCombo,
        isPressed: Boolean,
        offset: Float,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        val triggerSource = physicalInputSource(sourceDeviceId, keyCode, sourceController)
        if (bindingCombo.isSequence) {
            if (isPressed) {
                if (activeSequenceTriggerBindings.add(triggerSource)) {
                    handleInputEvent(
                        bindingCombo,
                        true,
                        offset,
                        fromMotion,
                        sourceKeyCode,
                        sourceDeviceId,
                        sourceController,
                    )
                }
            } else {
                activeSequenceTriggerBindings.remove(triggerSource)
            }
        } else {
            val resolvedBindingCombo = bindingCombo.takeIf { !it.isEmpty } ?: BindingCombo.of(legacyBinding)
            val dispatchedBindingCombo = if (isPressed) {
                activeTriggerBindings[triggerSource] = resolvedBindingCombo
                resolvedBindingCombo
            } else {
                activeTriggerBindings.remove(triggerSource) ?: resolvedBindingCombo
            }
            handleInputEvent(
                dispatchedBindingCombo,
                isPressed,
                offset,
                fromMotion,
                sourceKeyCode,
                sourceDeviceId,
                sourceController,
            )
        }
    }

    /**
     * Apply a binding to the virtual gamepad state and send to WinHandler.
     * Extracted from InputControlsView.handleInputEvent()
     */
    // offset: analog axis value for presses; must be 0f for releases (triggers use offset > 0f
    // to determine pressed state, sticks gate on isActionDown, everything else ignores offset)
    private fun handleInputEvent(
        bindingCombo: BindingCombo,
        isActionDown: Boolean,
        offset: Float = 0f,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        if (bindingCombo.isEmpty) return
        if (Binding.OPEN_RADIAL_MENU in bindingCombo.bindings) {
            handleInputEvent(
                Binding.OPEN_RADIAL_MENU,
                isActionDown,
                offset,
                fromMotion,
                sourceKeyCode,
                sourceDeviceId,
                sourceController,
            )
            return
        }
        if (bindingCombo.isSequence) {
            if (isActionDown) {
                performBindingSequence(
                    bindingCombo,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
            return
        }

        if (isActionDown) {
            bindingCombo.bindings.forEach { binding ->
                handleInputEvent(
                    binding,
                    true,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
        } else {
            bindingCombo.bindings.asReversed().forEach { binding ->
                handleInputEvent(
                    binding,
                    false,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
        }
    }

    private fun performBindingSequence(
        bindingCombo: BindingCombo,
        offset: Float,
        fromMotion: Boolean,
        sourceKeyCode: Int,
        sourceDeviceId: Int,
        sourceController: ExternalController?,
    ) {
        val pressDurationMs = Math.min(
            SEQUENCE_PRESS_MS,
            (bindingCombo.sequenceDelayMs - 1).coerceAtLeast(1).toLong(),
        )
        bindingCombo.bindings.forEachIndexed { index, binding ->
            sequenceHandler.postDelayed({
                handleInputEvent(
                    binding,
                    true,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
                activeSequenceBindings[binding] = (activeSequenceBindings[binding] ?: 0) + 1
                val sequenceSource = physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController)
                if (binding == Binding.GYRO_MODIFIER) activeSequenceGyroSources.add(sequenceSource)
                sendGamepadState()
                sequenceHandler.postDelayed({
                    val activeCount = activeSequenceBindings[binding] ?: return@postDelayed
                    if (binding == Binding.GYRO_MODIFIER) {
                        activeSequenceGyroSources.remove(sequenceSource)
                        handleInputEvent(
                            binding,
                            false,
                            0f,
                            fromMotion,
                            sourceKeyCode,
                            sourceDeviceId,
                            sourceController,
                        )
                    }
                    if (activeCount > 1) {
                        activeSequenceBindings[binding] = activeCount - 1
                    } else {
                        activeSequenceBindings.remove(binding)
                        if (binding != Binding.GYRO_MODIFIER) {
                            handleInputEvent(
                                binding,
                                false,
                                0f,
                                fromMotion,
                                sourceKeyCode,
                                sourceDeviceId,
                                sourceController,
                            )
                        }
                        sendGamepadState()
                    }
                }, pressDurationMs)
            }, index * bindingCombo.sequenceDelayMs.toLong())
        }
    }

    private fun cancelActiveSequences() {
        sequenceHandler.removeCallbacksAndMessages(null)
        activeSequenceGyroSources.toList().forEach { source ->
            handleInputEvent(
                Binding.GYRO_MODIFIER,
                false,
                0f,
                sourceKeyCode = source.keyCode,
                sourceDeviceId = source.deviceId,
            )
        }
        activeSequenceGyroSources.clear()
        if (activeSequenceBindings.isEmpty()) {
            activeSequenceTriggerBindings.clear()
            return
        }
        activeSequenceBindings.keys.toList().asReversed().forEach { binding ->
            if (binding != Binding.GYRO_MODIFIER) handleInputEvent(binding, false, 0f)
        }
        activeSequenceBindings.clear()
        activeSequenceTriggerBindings.clear()
        sendGamepadState()
    }

    private fun handleInputEvent(
        binding: Binding,
        isActionDown: Boolean,
        offset: Float = 0f,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        if (binding == Binding.NONE) return

        if (binding == Binding.GYRO_MODIFIER) {
            setGyroModifierPressed(sourceDeviceId, sourceKeyCode, sourceController, isActionDown)
            return
        }

        if (binding == Binding.OPEN_RADIAL_MENU) {
            if (!isActionDown && radialMenuPressed && !isRadialMenuOpenerDevice(sourceDeviceId)) return
            if (radialMenuPressed != isActionDown) {
                if (isActionDown) {
                    neutralizeMotionInputs(sourceKeyCode, sourceDeviceId, sourceController)
                } else if (fromMotion && sourceKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    activeAxisBindings.remove(
                        physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController),
                    )
                }
                radialMenuPressed = isActionDown
                radialMenuOpenedFromMotion = isActionDown && fromMotion
                radialMenuOpenerKeyCode = if (isActionDown) sourceKeyCode else KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = if (isActionDown) sourceDeviceId else UNKNOWN_DEVICE_ID
                onRadialMenuButtonStateChanged?.invoke(isActionDown, true)
                if (!isActionDown) onRadialMenuVectorChanged?.invoke(0f, 0f)
            } else if (!isActionDown) {
                radialMenuOpenedFromMotion = false
                radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
            }
            return
        }

        if (binding.isGamepad) {
            val winHandler = xServer?.winHandler
            val state = profile?.gamepadState

            if (state != null) {
                val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
                if (buttonIdx <= ExternalController.IDX_BUTTON_R2.toInt()) {
                    when (buttonIdx) {
                        ExternalController.IDX_BUTTON_L2.toInt() -> {
                            val triggerValue = if (offset > 0f) offset else if (isActionDown) 1f else 0f
                            state.triggerL = triggerValue
                            state.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), triggerValue > 0f)
                        }
                        ExternalController.IDX_BUTTON_R2.toInt() -> {
                            val triggerValue = if (offset > 0f) offset else if (isActionDown) 1f else 0f
                            state.triggerR = triggerValue
                            state.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), triggerValue > 0f)
                        }
                        else -> state.setPressed(buttonIdx, isActionDown)
                    }
                }
                else {
                    when (binding) {
                        Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN -> {
                            state.thumbLY = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT -> {
                            state.thumbLX = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN -> {
                            state.thumbRY = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT -> {
                            state.thumbRX = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_DPAD_UP  -> {
                            state.dpad[0] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_DOWN.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        Binding.GAMEPAD_DPAD_DOWN -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[0] = false
                            }
                        }
                       Binding.GAMEPAD_DPAD_LEFT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                              state.dpad[Binding.GAMEPAD_DPAD_RIGHT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                          }
                        }
                        Binding.GAMEPAD_DPAD_RIGHT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_LEFT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        else -> {}
                    }
                }

                if (winHandler != null) {
                    val controller = winHandler.getCurrentController()
                    if (controller != null) {
                        controller.state.copy(state)
                    }
                }
            }
        } else {
            // Handle special bindings
            if (binding == Binding.OPEN_NAVIGATION_MENU) {
                if (isActionDown) {
                    Timber.tag(TAG).d("Opening navigation menu from controller binding")
                    onOpenNavigationMenu?.invoke()
                }
            } else if (binding == Binding.SHOW_KEYBOARD) {
                if (isActionDown) {
                    if (!showKeyboardPressed) {
                        showKeyboardPressed = true
                        Timber.tag(TAG).d("Showing keyboard from controller binding")
                        onShowKeyboard?.invoke()
                    }
                } else {
                    showKeyboardPressed = false
                }
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                updateMouseMoveContribution(binding, isActionDown, offset, sourceKeyCode, sourceDeviceId)
            } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                updateMouseMoveContribution(binding, isActionDown, offset, sourceKeyCode, sourceDeviceId)
            } else if (handleScrollBinding(binding, isActionDown)) {
                // Mouse wheel events are pulses, not held button state.
            } else {
                // For keyboard/mouse button bindings, inject into XServer
                val pointerButton = binding.pointerButton
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonPress(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, true) }
                    }
                } else {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonRelease(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, false) }
                    }
                }
            }
        }
    }

    private fun setGyroModifierPressed(
        sourceDeviceId: Int,
        sourceKeyCode: Int,
        sourceController: ExternalController?,
        pressed: Boolean,
    ) {
        val source = physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController)
        val changed = if (pressed) {
            activeGyroModifierSources.add(source)
        } else {
            activeGyroModifierSources.remove(source)
        }
        if (changed) onGyroModifierChanged?.invoke(source, pressed)
    }

    private fun physicalInputSource(
        sourceDeviceId: Int,
        sourceKeyCode: Int,
        sourceController: ExternalController?,
    ): PhysicalInputSource {
        val resolvedDeviceId = if (sourceDeviceId != UNKNOWN_DEVICE_ID) {
            sourceDeviceId
        } else {
            sourceController?.deviceId ?: UNKNOWN_DEVICE_ID
        }
        return PhysicalInputSource(resolvedDeviceId, sourceKeyCode)
    }

    private fun releaseGyroModifierSources(deviceId: Int? = null) {
        activeGyroModifierSources.toList().forEach { source ->
            if (deviceId == null || source.deviceId == deviceId) {
                activeGyroModifierSources.remove(source)
                onGyroModifierChanged?.invoke(source, false)
            }
        }
    }

    private fun closeRadialMenuIfOpen(commit: Boolean) {
        if (!radialMenuPressed) return
        radialMenuPressed = false
        radialMenuOpenedFromMotion = false
        radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
        radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
        onRadialMenuVectorChanged?.invoke(0f, 0f)
        onRadialMenuButtonStateChanged?.invoke(false, commit)
    }

    private fun neutralizeMotionInputs(
        exceptKeyCode: Int,
        sourceDeviceId: Int,
        controller: ExternalController?,
    ) {
        val exceptSource = if (exceptKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
            null
        } else {
            physicalInputSource(sourceDeviceId, exceptKeyCode, controller)
        }
        releaseActiveAxes(exceptSource)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_L2, exceptKeyCode)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_R2, exceptKeyCode)
        clearMouseMoveContributions()
        clearScrollRepeats()
    }

    private fun neutralizeTrigger(controller: ExternalController?, keyCode: Int, exceptKeyCode: Int) {
        if (keyCode == exceptKeyCode) return
        val activeController = controller ?: profile?.getController("*") ?: return
        val triggerValue = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> activeController.state.triggerL
            KeyEvent.KEYCODE_BUTTON_R2 -> activeController.state.triggerR
            else -> 0f
        }
        if (triggerValue <= 0f) return
        activeController.getControllerBinding(keyCode)
            ?.takeIf { Binding.OPEN_RADIAL_MENU !in it.bindingCombo.bindings }
            ?.let {
                handleInputEvent(
                    it.bindingCombo,
                    false,
                    0f,
                    fromMotion = true,
                    sourceKeyCode = keyCode,
                    sourceController = activeController,
                )
            }
    }

    private fun isRadialMenuOpenerDevice(deviceId: Int): Boolean {
        return radialMenuOpenerDeviceId == UNKNOWN_DEVICE_ID || deviceId == radialMenuOpenerDeviceId
    }

    private fun handleRadialMenuNavigationKey(event: KeyEvent, keyCode: Int): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> true
                else -> false
            }
        }
        val vector = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
            KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
            KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
            else -> return false
        }
        onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        return true
    }

    private fun updateRadialMenuVector(controller: ExternalController) {
        val vector = radialSelectionVectorForAxes(
            controller.state.thumbLX,
            controller.state.thumbLY,
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
        ) ?: radialSelectionVectorForAxes(
            controller.state.thumbRX,
            controller.state.thumbRY,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
        ) ?: radialSelectionVectorForAxes(
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )

        if (vector == null) {
            onRadialMenuVectorChanged?.invoke(0f, 0f)
        } else {
            onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        }
    }

    private fun radialSelectionVectorForAxes(
        x: Float,
        y: Float,
        xAxis: Int,
        yAxis: Int,
    ): Pair<Float, Float>? {
        val filteredX = radialSelectionComponent(x, xAxis)
        val filteredY = radialSelectionComponent(y, yAxis)
        return if (Math.abs(filteredX) <= ControlElement.STICK_DEAD_ZONE &&
            Math.abs(filteredY) <= ControlElement.STICK_DEAD_ZONE
        ) {
            null
        } else {
            filteredX to filteredY
        }
    }

    private fun radialSelectionComponent(value: Float, axis: Int): Float {
        if (Math.abs(value) <= ControlElement.STICK_DEAD_ZONE) return 0f
        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, Mathf.sign(value))
        return if (keyCode == radialMenuOpenerKeyCode) 0f else value
    }

    private fun isRadialMenuMotionOpenerPressed(controller: ExternalController): Boolean {
        val axes = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )
        val values = floatArrayOf(
            controller.state.thumbLX,
            controller.state.thumbLY,
            controller.state.thumbRX,
            controller.state.thumbRY,
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
        )

        for (i in axes.indices) {
            if (Math.abs(values[i]) <= ControlElement.STICK_DEAD_ZONE) continue
            val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
            if (keyCode == radialMenuOpenerKeyCode) {
                return true
            }
        }

        return (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_L2 && controller.state.triggerL > 0f) ||
            (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_R2 && controller.state.triggerR > 0f)
    }
}
