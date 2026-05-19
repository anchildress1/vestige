package dev.anchildress1.vestige.ui.patterns

import dev.anchildress1.vestige.model.PatternKind
import java.util.Locale

/** POC eyebrow slot bound to the stored pattern primitive, not fake archetype/demo copy. */
fun patternKindLabel(kind: PatternKind): String = kind.serial.replace('_', ' ').uppercase(Locale.US)
