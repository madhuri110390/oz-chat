# Implementation Plan

1.  **Promotional Link Redirection (Play Store/App)**
    *   Currently, the promotional text provides a link like `https://openzipper.com/ozchat/profile/$username`.
    *   This link opens the app if it's installed (because of the `AndroidManifest.xml` intent filter for `https://openzipper.com/ozchat`), BUT the user wants it to act as a *global* link that redirects to the Play Store if the app is *not* installed.
    *   If they want a *global link like whatsapp*, it usually means `https://play.google.com/store/apps/details?id=com.openzipper.ozchat` OR they have set up `openzipper.com` to redirect but it's not working, so maybe use `https://play.google.com/store/apps/details?id=com.openzipper.ozchat` as the download link.
    *   I'll change `Download OZ Chat: https://openzipper.com/ozchat` to `Download OZ Chat: https://play.google.com/store/apps/details?id=com.openzipper.ozchat` in `ImageBrandingUtils.kt` and `TimelineFragment.kt`.
    *   Also, in `strings.xml`, update the `invite_friends_text` string to include the Play Store link as a fallback or explicit "Download App" link, or just replace the fallback text. Wait, if I append `\n\nDownload App: https://play.google.com/store/apps/details?id=com.openzipper.ozchat`, it explicitly provides the download link.

2.  **Screen Share Blurring Issue**
    *   User statement: "also on screen share fix interface blurring issue"
    *   I need to check where screen sharing is handled (likely `VectorCallActivity` or `CallControlsBottomSheet` or `screen share` layout) and see if a blur is improperly applied to the UI or video track.

3.  **Video Call Crashes**
    *   Crash 1: "Click on Screen Share > On the sharing acceptance Screen - (select one app to share) > let the App open > Now Go back to OZ Chat App > App Crashed." -> This sounds like a lifecycle issue (resume/pause) while screen sharing intent is pending or active.
    *   Crash 2: "Click on Screen Share > Now Ask User B (Bob Lee) to click on Stop Sharing Option > Now as a User A (Ned) Click on Stop Sharing > Wait for 2-3 seconds > App Crashed." -> Sounds like a race condition when stopping a screen share that may have already been stopped or nullified.
    *   "v.call loading time reduce" -> Investigate `VectorCallActivity` layout (maybe too many views or heavy work on main thread during `onCreate`).

## Crash Fixes Overview
1.  **Crash on Stop Sharing from Remote then Local**
    *   Symptom: Stop sharing triggered from `CallControlsBottomSheet` via `callViewModel.handle(VectorCallViewActions.ToggleScreenSharing)` calls `call?.stopSharingScreen()`. If the remote user stops first, our local state `isSharingScreen` might be out of sync or the track is already disposed.
    *   Fix in `WebRtcCall.kt: stopSharingScreen()`: Ensure `videoCapturer` and `screenSender` cleanup is wrapped in `runCatching` (which it largely is), and check that we don't dispatch UI tasks or null references improperly. `localVideoTrack` is disposed without being removed from `localMediaStream` gracefully if it was stopped remotely but locally it wasn't updated? Wait... When remote stops sharing, they send `UpdateCallType.SCREEN_SHARE_STOP` (or just `VOICE`/`VIDEO`). Local peer receives it in `VectorCallViewModel`:
        ```kotlin
                UpdateCallType.VOICE -> {
                    withState { state ->
                        // ...
                        setState { copy(isVideoCall = false, isVideoEnabled = false, isSharingScreen = false) }
                    }
                }
        ```
        If local then clicks "Stop Sharing", `ToggleScreenSharing` sees `state.isSharingScreen == false` and thinks we should *start* sharing? Or wait, if `isSharingScreen` is out of sync or if there is a race condition.
        Wait, `CallControlsBottomSheet` uses `state.isSharingScreen`. If remote stops sharing, their stream stops. *Local* screen sharing is controlled locally. If remote user stops *their* screen share, it should only affect their track.
        Wait, `UpdateCallType.SCREEN_SHARE` and `VOICE` are sent when a peer changes THEIR tracks. Why does `VectorCallViewModel` say `copy(isSharingScreen = true)` when receiving `UpdateCallType.SCREEN_SHARE`?
        ```kotlin
                UpdateCallType.SCREEN_SHARE -> {
                    // Remote peer started screen sharing — reflect it so fullscreen renderer appears
                    setState { copy(isSharingScreen = true) }
                }
        ```
        This is a shared state variable `isSharingScreen` for BOTH local and remote sharing? Yes! `VectorCallViewState.isSharingScreen` is set to `true` if *either* local starts sharing OR remote starts sharing!
        If local is sharing, `VectorCallViewModel.handleToggleScreenSharing` checks `state.isSharingScreen`.
        ```kotlin
    private suspend fun handleToggleScreenSharing(isSharingScreen: Boolean) {
        if (isSharingScreen) {
             call?.stopSharingScreen()
             setState { copy(isSharingScreen = false) }
             // ...
        } else {
             _viewEvents.post(VectorCallViewEvents.ShowScreenSharingPermissionDialog)
        }
    }
        ```
        If B (remote) clicks "Stop Sharing", A (local) receives `VOICE` and sets `isSharingScreen = false`. Then if A clicks "Start Sharing" (because the button now says "Share Screen"), it tries to start. But what if A was also sharing? Wait, the crash report says:
        "Open the App > Call User B (Bob Lee) > Click on Screen Share > Now Ask User B (Bob Lee) to click on Stop Sharing Option > Now as a User A (Ned) Click on Stop Sharing > Wait for 2-3 seconds > App Crashed."
        Wait! If A is sharing their screen, why would asking B to click "Stop Sharing" do anything? B is not sharing. Does B even have a "Stop Sharing" button? No, but maybe B's button says "Stop Sharing" because `isSharingScreen` is `true` for B too!
        So if B clicks "Stop Sharing", B calls `stopSharingScreen()` even though B isn't sharing!
        When B calls `stopSharingScreen()`, it does:
        ```kotlin
        runCatching { videoCapturer?.stopCapture() } // null
        videoCapturer = null
        runCatching { localVideoTrack?.setEnabled(false) } // disables their camera!
        screenSender?.let { ... } // null
        runCatching { localVideoTrack?.dispose() } // disposes their camera track!
        localVideoTrack = null
        if (isVideoMode) { peerConnectionFactoryProvider.get()?.let { configureVideoTrack(it) } } // recreates camera track
        ```
        Then B sends `VOICE` (because `isVideoMode` might be false, or it sends `SCREEN_SHARE_STOP`).
        Then A receives `VOICE` or whatever, and sets `isSharingScreen = false`.
        Then A clicks "Stop Sharing"... wait, if A's `isSharingScreen` is now `false`, A's button says "Share Screen". If A clicks it, it starts screen sharing again. But A was already sharing! The old capturer might still be running locally, leaking or crashing when creating a new one!
        If A clicks "Share Screen" while already capturing, it might crash in MedicalProjection.
        Let's separate `isSharingScreen` into `isLocalScreenSharing` and `isRemoteScreenSharing`.
        Or, at least in `CallControlsBottomSheet`:
        ```kotlin
        views.callControlsShareScreen.title = getString(
                if (state.isLocalScreenSharing) CommonStrings.call_stop_screen_sharing else CommonStrings.call_start_screen_sharing
        )
        ```

    Let's check `VectorCallViewState`. It only has `isSharingScreen: Boolean`.
    And `WebRtcCall.isSharingScreen()` returns what?
    Let's search for `fun isSharingScreen` in `WebRtcCall.kt`.
Let's modify `VectorCallViewState` to have both `isLocalScreenSharing: Boolean` and `isRemoteScreenSharing: Boolean`? Wait, `VectorCallViewState` currently has `isSharingScreen`.
Let's see: `isSharingScreen` is used for:
1. `CallControlsBottomSheet`: to show Stop Sharing vs Share Screen -> this should be `isLocalScreenSharing`! If the remote person is sharing, it shouldn't say "Stop Sharing" on my controls unless it's stopping my share!
2. `VectorCallActivity.renderState`: to decide whether to show the fullscreen renderer. This should be `isLocalScreenSharing || isRemoteScreenSharing`.
3. `VectorCallActivity.renderPiPMode`: same thing.

Currently, `isSharingScreen` is set in ViewModel:
- In `setupCallWithCurrentState`: `isSharingScreen = webRtcCall.isSharingScreen()` (which returns true ONLY if the local track is the screen capture track! `WebRtcCall.isSharingScreen()` clearly checks `localVideoTrack`!).
- In `onCallEnded`: `isSharingScreen = false`
- In `UpdateCallType.SCREEN_SHARE`: `isSharingScreen = true` <-- THIS IS THE BUG! If remote starts sharing, `UpdateCallType.SCREEN_SHARE` is fired, and local sets `isSharingScreen = true`! Then local's "Share Screen" button becomes "Stop Sharing"! And if local presses it, local tries to stop a screen share that local doesn't have, causing a crash! Wait, if local presses it, it does `startSharingScreen` or `stopSharingScreen`? It checks `VectorCallViewState.isSharingScreen`, which is true, so it calls `handleToggleScreenSharing(isSharingScreen = true)`, which does `call?.stopSharingScreen()`. This does absolutely nothing useful locally (capturer is null, no screen track), but it creates a mess!

AND `UpdateCallType.SCREEN_SHARE` and `UpdateCallType.VOICE` both set `isSharingScreen = false` or true based on REMOTE actions!
So indeed, `isSharingScreen` parameter in `VectorCallViewState` is overloaded. It's used for both local and remote states, but the UI assumes it means "is local sharing".

Fix:
Add `isRemoteSharingScreen` to `VectorCallViewState`.
Update `VectorCallViewModel` to set `isRemoteSharingScreen` when receiving `UpdateCallType.SCREEN_SHARE` or `UpdateCallType.SCREEN_SHARE_STOP` (or when `UpdateCallType.VOICE` or `UpdateCallType.VIDEO` comes in, we clear it).
Wait, `WebRtcCall.Listener.onRemoteScreenShareChanged(isSharing: Boolean)` exists!
```kotlin
        override fun onRemoteScreenShareChanged(isSharing: Boolean) {
            setState { copy(isSharingScreen = isSharing) } // BUG! This overwrites local sharing state!
        }
```
Let's change it:

1. In `VectorCallViewState`:
```kotlin
        val isLocalScreenSharing: Boolean = false,
        val isRemoteScreenSharing: Boolean = false
```
Wait, I shouldn't just rename it because it requires changing many places. What if I keep `isSharingScreen` which means "local screen sharing", and add `isRemoteScreenSharing`?
And what about the rendering logic in `VectorCallActivity`?
```kotlin
views.fullscreenRenderer.isVisible = state.isVideoCall || state.isSharingScreen || state.isRemoteScreenSharing
```
Actually, wait, in `VectorCallActivity.kt`:
```kotlin
                        if (state.isVideoCall || state.isSharingScreen) {
                            views.fullscreenRenderer.isVisible = true
                            views.pipRendererWrapper.isVisible = state.isVideoCall
```
If we add `isRemoteScreenSharing`:
```kotlin
                        if (state.isVideoCall || state.isSharingScreen || state.isRemoteScreenSharing) {
```

Let's modify `VectorCallViewState`:
Change `isSharingScreen` to `isSharingScreen` (local). Let's keep the name so `CallControlsBottomSheet` works unchanged (it already uses `state.isSharingScreen`).
Add `isRemoteScreenSharing: Boolean = false`.

2. In `VectorCallViewModel`:
```kotlin
        override fun onRemoteScreenShareChanged(isSharing: Boolean) {
            setState { copy(isRemoteScreenSharing = isSharing) } // Fix
        }

        override fun onCallUpdateTypeReceived(mxCall: MxCall, update: CallUpdateTypeContent) {
            call = callManager.getCallById(mxCall.callId)
            when (update.updateCallType) {
                UpdateCallType.VOICE -> {
                    // ...
                            // Normal voice call update — also clears screen share state if it was active
                            setState { copy(isVideoCall = false, isVideoEnabled = false, isRemoteScreenSharing = false) } // fix
                    // ...
                }
                UpdateCallType.VIDEO -> {
                    setState { copy(isVideoCall = true, isVideoEnabled = true, isRemoteScreenSharing = false) } // fix
                }
                UpdateCallType.SCREEN_SHARE -> {
                    // Remote peer started screen sharing
                    setState { copy(isRemoteScreenSharing = true) } // Fix
                }
```

Wait, `WebRtcCall.kt` line 1066:
```kotlin
    fun onRemoteScreenShareChanged(isSharing: Boolean) {
        listeners.forEach {
            tryOrNull { it.onRemoteScreenShareChanged(isSharing) }
        }
    }
```
Where is this called from? `onRemoteScreenShareChanged` is called from `VectorCallViewModel` maybe? No, `WebRtcCall.kt` has it. Let's grep `onRemoteScreenShareChanged`.

Wait, the crashes:
"Open the App > Call User B (Bob Lee) > Click on Screen Share > On the sharing acceptance Screen - (select one app to share) > let the App open > Now Go back to OZ Chat App > App Crashed." -> This is a DIFFERENT crash! Crash during local screen share start/resume.
"Open the App > Call User B (Bob Lee) > Click on Screen Share > Now Ask User B (Bob Lee) to click on Stop Sharing Option > Now as a User A (Ned) Click on Stop Sharing > Wait for 2-3 seconds > App Crashed." -> Wait, B clicks "Stop Sharing Option"? WHY does B have a stop sharing option if A is sharing? B has it because `UpdateCallType.SCREEN_SHARE` told B that screen sharing is happening, and B's `VectorCallViewModel` set `isSharingScreen = true`! Then if B clicks B's "Stop Sharing", B calls `stopSharingScreen()` which crashes.
The second crash: "Open the App > Call User B (Bob Lee) > Click on Screen Share > On the sharing acceptance Screen - (select one app to share) > let the App open > Now Go back to OZ Chat App > App Crashed."
Wait, if you select one app to share, `ScreenCapturerAndroid` might return null or crash when started?
Or it could be that when you go back to the app, the activity restarts and `startCapturingScreen` throws an exception, or `resumeLocalVideoPipeline` crashes.

Let's check the stack trace if available. The user hasn't provided the exact stack trace for these crashes, just steps to reproduce.
What happens when you select one app to share and then go back?
Android 14 (API 34) introduced partial screen sharing (sharing a single app). If you share a single app, and then you bring OZ Chat to the foreground, the shared app might be pushed to the background or the screen capture might change state or crash because the capture targets an app that's paused?

Wait! In `WebRtcCall.startScreenSharing`:
```kotlin
    fun startSharingScreen(videoCapturer: VideoCapturer) {
        ...
        this.videoCapturer = videoCapturer
        val videoSource = factory.createVideoSource(videoCapturer.isScreencast)
        startCapturingScreen(videoCapturer, videoSource)
        ...
    }
```
Is `startCapturingScreen` missing `runCatching` for partial screen share issues?
Wait, if you share a single app, and then go back to OZ Chat, `VectorCallActivity` might enter picture-in-picture mode or onResume:
```kotlin
    override fun onResume() {
        super.onResume()
        // wait, we don't have onResume shown in the grep.
    }
```
If you go back to the app, `CallState.Connected` is already active, so `renderState` is called.
Wait, `CallAndroidService` has a foreground notification for the call.

In `VectorCallActivity.startScreenSharing`:
```kotlin
        val videoCapturer = ScreenCapturerAndroid(activityResult.data, object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.i("User revoked the screen capturing permission")
                withState(callViewModel) {
                    if (it.isSharingScreen) {
                        callViewModel.handle(VectorCallViewActions.ToggleScreenSharing)
                    }
                }
            }
        })
        callViewModel.handle(VectorCallViewActions.StartScreenSharing(videoCapturer))
```
Does `startScreenSharing` crash inside WebRTC?
In modern Android (14+), starting MediaProjection must be done with a foreground service properly configured with `mediaProjection` type. If it's not, it will crash with a SecurityException when `videoCapturer.startCapture()` is called!
Does `CallAndroidService` have `dataSync|phoneCall|mediaProjection` or similar? Wait, MediaProjection foreground service type is required for screen capture.
Let's check `CallAndroidService.kt` and `AndroidManifest.xml`.
