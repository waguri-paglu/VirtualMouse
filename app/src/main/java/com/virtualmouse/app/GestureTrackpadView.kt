package com.virtualmouse.app

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * Retained for source compatibility; touch events are now intercepted
 * directly in the Linux kernel via ioctl(EVIOCGRAB) in vmouse_daemon.c.
 */
class GestureTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
