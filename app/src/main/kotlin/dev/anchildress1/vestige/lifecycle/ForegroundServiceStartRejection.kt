package dev.anchildress1.vestige.lifecycle

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build

internal fun Throwable.isForegroundServiceStartNotAllowed(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
    this is ForegroundServiceStartNotAllowedException
