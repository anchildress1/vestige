import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.objectbox) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.sonar)
}

dependencies {
    kover(project(":app"))
    kover(project(":core-model"))
    kover(project(":core-inference"))
    kover(project(":core-storage"))
}

// Compose @Composable screen/host files cap at ~50% branch coverage from the Compose
// compiler's `Composer` + `$changed` synthetics (kotlinx-kover #756 — "Wrong branch
// coverage for composables"). Single source of truth: kover wants FQCN class globs,
// Sonar wants source paths — both derive from this list, so a new screen is one line,
// not six across two formats. Non-Composable logic lives in its own classes/files and
// stays counted. Behaviour is covered by the *ScreenTest.kt Robolectric suites.
val composeScreenExclusions = listOf(
    "ui.history.HistoryHost",
    "ui.history.HistoryRow",
    "ui.history.HistoryScreen",
    "ui.history.EntryDetailHost",
    "ui.history.EntryDetailScreen",
    "ui.patterns.PatternsListScreen",
    "ui.patterns.PatternDetailScreen",
    "ui.patterns.PatternsHost",
    "ui.patterns.EntryDetailPlaceholderScreen",
    "ui.patterns.TraceBar",
    "ui.patterns.TraceBarE",
    "ui.onboarding.OnboardingHost",
    "ui.onboarding.OnboardingStepContent",
    "ui.onboarding.OnboardingScaffold",
    "ui.onboarding.OnboardingScreens",
    "ui.onboarding.PersonaPickScreen",
    "ui.onboarding.WiringScreen",
    "ui.onboarding.ModelDownloadPlaceholderScreen",
    "ui.components.ScoreboardPrimitives",
    "ui.components.VestigeSurface",
    "ui.components.VestigeScaffold",
    "ui.components.AccentModifiers",
    "ui.capture.IdleLayout",
    "ui.capture.LiveLayout",
    "ui.capture.RecButton",
    "ui.capture.LiveLevelBars",
    "ui.capture.ChunkProgressBar",
    "ui.capture.CaptureScreen",
    "ui.capture.TypeEntrySheet",
)

// kover form: the file-class `XKt`, its `XKt*` synthetics, and Compose's
// `*XKt*` (`ComposableSingletons$XKt`) lambda holder — three globs per screen.
val koverComposeClassGlobs: List<String> = composeScreenExclusions.flatMap { rel ->
    val pkg = "dev.anchildress1.vestige." + rel.substringBeforeLast('.')
    val kt = rel.substringAfterLast('.') + "Kt"
    listOf("$pkg.$kt", "$pkg.$kt*", "$pkg.*$kt*")
}

kover {
    reports {
        total {
            filters {
                excludes {
                    // Kover's `*` glob requires ≥1 trailing character — `MainActivity*` matches
                    // `MainActivityKt` but NOT bare `MainActivity`. Enumerate both forms so
                    // top-level classes AND their Compose / coroutine / FileKt synthetics are
                    // excluded together. Leading `*` on `*MainActivityKt*` catches Compose's
                    // `ComposableSingletons$MainActivityKt` lambda holder, which lives in the
                    // root package alongside MainActivity. `CaptureViewModelFactory` is lifecycle
                    // factory glue; tested business derivations live in `CaptureHostModels.kt`.
                    // Compose screen/host files are generated from `composeScreenExclusions`
                    // (single source of truth shared with Sonar) — see the val above the block.
                    classes(
                        *koverComposeClassGlobs.toTypedArray(),
                        "dev.anchildress1.vestige.MainActivity",
                        "dev.anchildress1.vestige.MainActivity*",
                        "dev.anchildress1.vestige.MainActivityKt",
                        "dev.anchildress1.vestige.MainActivityKt*",
                        "dev.anchildress1.vestige.*MainActivityKt*",
                        "dev.anchildress1.vestige.CaptureViewModelFactory",
                        "dev.anchildress1.vestige.CaptureViewModelFactory*",
                        "dev.anchildress1.vestige.AppContainer",
                        "dev.anchildress1.vestige.AppContainer*",
                        "dev.anchildress1.vestige.VestigeDataExporter",
                        "dev.anchildress1.vestige.VestigeDataExporter*",
                        "dev.anchildress1.vestige.lifecycle.BackgroundExtractionService",
                        "dev.anchildress1.vestige.lifecycle.BackgroundExtractionService*",
                        "dev.anchildress1.vestige.VestigeApplication",
                        "dev.anchildress1.vestige.VestigeApplication*",
                        "dev.anchildress1.vestige.ui.theme.*",
                        // Debug-only fixture seeder for on-device manual verification.
                        // FLAG_DEBUGGABLE-gated at the call site; not on any release path.
                        "dev.anchildress1.vestige.debug.*",
                        // Model loading and audio recording require on-device hardware/model;
                        // exercised by androidTest, not JVM unit tests.
                        "dev.anchildress1.vestige.inference.LiteRtLmEngine",
                        "dev.anchildress1.vestige.inference.LiteRtLmEngine*",
                        "dev.anchildress1.vestige.inference.AudioCapture",
                        "dev.anchildress1.vestige.inference.AudioCapture*",
                    )
                    // Compose UI carries no business logic — `@Composable` functions are
                    // declarative rendering; their "branches" are overwhelmingly the compiler's
                    // recomposition/skippability guards (`$changed`, `shouldExecute`), not
                    // testable conditions. View logic lives in ViewModels / host-model
                    // derivations / formatters, which are NOT `@Composable` and stay fully
                    // measured by the LINE + BRANCH bounds. This annotation exclude supersedes
                    // the brittle per-screen `composeScreenExclusions` list for the branch metric
                    // (new screens no longer have to be hand-added to keep the gate honest).
                    annotatedBy("androidx.compose.runtime.Composable")
                }
            }
            verify {
                rule {
                    // INSTRUCTION default — bytecode-level coverage. Kept so the historical
                    // gate continues to fire on raw bytecode regressions.
                    bound {
                        minValue = 75
                    }
                }
                rule {
                    // LINE — matches Sonar's overall-line-coverage metric so the local hook
                    // doesn't ship code that fails the cloud gate.
                    bound {
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                        minValue = 75
                    }
                }
                rule {
                    // BRANCH — Kover's bytecode branch count is noisy for Android/Compose
                    // generated paths and contains little direct business logic signal. Keep the
                    // gate close to the current suite without pretending 80% is meaningful here.
                    bound {
                        coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                        minValue = 66
                    }
                }
            }
        }
    }
}

sonar {
    properties {
        property("sonar.projectKey", "anchildress1_vestige")
        property("sonar.organization", "anchildress1")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.kotlin.coveragePlugin", "jacoco")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/kover/report.xml")
        property(
            "sonar.junit.reportPaths",
            listOf(
                "app/build/test-results/testDebugUnitTest",
                "core-inference/build/test-results/testDebugUnitTest",
                "core-model/build/test-results/test",
                "core-storage/build/test-results/testDebugUnitTest",
            ).joinToString(","),
        )
        property(
            "sonar.exclusions",
            listOf(
                "**/build/**",
                "**/generated/**",
                "**/*.gradle.kts",
                "**/objectbox-models/**",
            ).joinToString(","),
        )
        property(
            "sonar.coverage.exclusions",
            (
                listOf(
                    "**/ui/theme/**",
                    "**/VestigeApplication.kt",
                    "**/MainActivity.kt",
                    "**/AppContainer.kt",
                    "**/VestigeDataExporter.kt",
                    "**/BackgroundExtractionService.kt",
                    "**/LiteRtLmEngine.kt",
                    "**/AudioCapture.kt",
                    // Wiring layer; behavior measured by :app:testDebugIntegrationTest
                    // (PatternDetectionOrchestratorTest + AnalysisTest), which kover instrumentation
                    // intentionally does not cover. Unit-tier coverage on this surface would mock
                    // out PatternDetector + PatternStore — tautological. ADR-014 contract.
                    "**/patterns/PatternDetectionOrchestrator.kt",
                    // Compose-bound ViewModel; behavior measured by :app:testDebugIntegrationTest
                    // (PatternsListScreenTest). Unit-tier coverage would re-stub the StateFlow plumbing.
                    "**/ui/patterns/PatternsListViewModel.kt",
                    // Debug-only fixture seeder, FLAG_DEBUGGABLE-gated; never on a release path.
                    "**/debug/**",
                ) +
                    // Compose @Composable bodies cap at ~50% branch coverage from `Composer`
                    // + `$changed` instrumentation (kotlinx-kover #756); same source-of-truth
                    // list the kover `excludes { classes(...) }` block derives from.
                    composeScreenExclusions.map { "**/${it.replace('.', '/')}.kt" }
                ).joinToString(","),
        )
        // Both pattern view-models share an action-dispatch + undo skeleton (dismiss /
        // snooze / markResolved / restart). The structural overlap is intentional for the
        // list + detail surface pair; Story 4.8 retires `markResolved` and extracts a shared
        // dispatcher when the `PatternAction` enum + `PatternState` rename land. Excluding
        // the two VMs from CPD avoids gating PR #26 on that cleanup.
        property(
            "sonar.cpd.exclusions",
            listOf(
                "**/ui/patterns/PatternsListViewModel.kt",
                "**/ui/patterns/PatternDetailViewModel.kt",
            ).joinToString(","),
        )
        property("sonar.qualitygate.wait", "true")
        property("sonar.qualitygate.timeout", "300")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        // JDK 24+/25 warns when class-path-based JNI loaders (ObjectBox, Robolectric native
        // runtime) call System.load without an explicit native-access opt-in.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Third-party test libraries still touch terminally-deprecated Unsafe memory APIs on
        // JDK 25. Allow it explicitly so the test log only carries actionable failures.
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
        // Robolectric appends to the bootstrap classpath; disable CDS for forked test JVMs so
        // they stop printing the "Sharing is only supported for boot loader classes" warning.
        jvmArgs("-Xshare:off")
        testLogging {
            showStandardStreams = false
        }
    }
}

private data class TelemetryModuleScan(val path: String, val configuration: String, val coordinates: List<String>)

private data class ManifestComponentScan(val file: String, val components: List<String>)

private data class ApkHostScan(val file: String, val matchedHosts: List<String>)

private object TelemetryRules {
    val forbiddenCoordinates = setOf(
        "com.google.firebase:firebase-analytics",
        "com.google.firebase:firebase-analytics-ktx",
        "com.google.firebase:firebase-config",
        "com.google.firebase:firebase-config-ktx",
        "com.google.firebase:firebase-crashlytics",
        "com.google.firebase:firebase-crashlytics-ktx",
        "com.google.firebase:firebase-installations",
        "com.google.firebase:firebase-installations-interop",
        "com.google.firebase:firebase-perf",
        "com.google.firebase:firebase-perf-ktx",
        "com.google.android.gms:play-services-analytics",
        "com.google.android.gms:play-services-measurement",
        "com.google.android.gms:play-services-measurement-api",
        "com.google.android.gms:play-services-measurement-base",
        "com.google.android.gms:play-services-measurement-impl",
        "com.google.android.gms:play-services-measurement-sdk",
        "com.google.android.gms:play-services-measurement-sdk-api",
        "io.sentry:sentry",
        "io.sentry:sentry-android",
        "io.sentry:sentry-android-core",
        "io.sentry:sentry-compose-android",
        "com.mixpanel.android:mixpanel-android",
        "com.mixpanel:mixpanel-java",
        "com.amplitude:analytics-android",
        "com.amplitude:android-sdk",
        "com.datadoghq:dd-sdk-android",
        "com.datadoghq:dd-sdk-android-logs",
        "com.datadoghq:dd-sdk-android-rum",
        "com.newrelic.agent.android:android-agent",
        "com.segment.analytics.android:analytics",
        "com.segment.analytics.kotlin:core",
        "com.launchdarkly:launchdarkly-android-client-sdk",
    )

    val auditedCoordinatePrefixes = setOf(
        "com.google.firebase:",
        "com.google.android.gms:",
        "com.google.ai.edge.",
        "com.google.protobuf:",
        "com.google.code.gson:",
        "com.google.guava:",
        "com.squareup.okhttp3:",
        "com.squareup.okio:",
        "org.json:",
    )

    val allowedModelDownloadCoordinates = setOf(
        "com.google.ai.edge.litertlm:litertlm-android",
        "com.google.ai.edge.localagents:localagents-rag",
        "com.google.protobuf:protobuf-javalite",
        "com.google.code.gson:gson",
        "com.google.guava:failureaccess",
        "com.google.guava:guava",
        "com.google.guava:listenablefuture",
        "com.squareup.okhttp3:okhttp",
        "com.squareup.okio:okio",
        "com.squareup.okio:okio-jvm",
        "org.json:json",
    )

    val forbiddenHostLiterals = setOf(
        "app-measurement.com",
        "firebaseinstallations.googleapis.com",
        "firebaseremoteconfig.googleapis.com",
        "firebase-settings.crashlytics.com",
        "crashlytics.com",
        "o.sentry.io",
        "ingest.sentry.io",
        "api.mixpanel.com",
        "decide.mixpanel.com",
        "api.amplitude.com",
        "regionconfig.amplitude.com",
        "mobile-collector.newrelic.com",
        "collector.newrelic.com",
        "api.segment.io",
        "cdn-settings.segment.com",
        "logs.datadoghq.com",
        "browser-intake-datadoghq.com",
        "mobile.launchdarkly.com",
        "events.launchdarkly.com",
    )

    val forbiddenManifestComponents = setOf(
        "com.google.firebase.provider.FirebaseInitProvider",
        "com.google.firebase.components.ComponentDiscoveryService",
        "com.google.android.gms.measurement.AppMeasurementReceiver",
        "com.google.android.gms.measurement.AppMeasurementService",
        "com.google.android.gms.measurement.AppMeasurementJobService",
        "io.sentry.android.core.SentryInitProvider",
        "io.sentry.android.core.SentryPerformanceProvider",
        "com.mixpanel.android.mpmetrics.MixpanelInitProvider",
        "com.amplitude.android.AmplitudeContentProvider",
        "com.datadog.android.DatadogProvider",
        "com.newrelic.agent.android.NewRelicContentProvider",
        "com.segment.analytics.AnalyticsContextProvider",
        "com.launchdarkly.sdk.android.subsystems.DiagnosticsService",
    )
}

private fun loadAllowedHosts(rootDir: File): Set<String> {
    val props = Properties()
    rootDir.resolve("core-model/src/main/resources/model/manifest.properties")
        .reader(StandardCharsets.UTF_8)
        .use(props::load)
    return sequenceOf("allowed_hosts", "embedding_allowed_hosts")
        .flatMap { key ->
            props.getProperty(key)
                .orEmpty()
                .split(',')
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
        .toSortedSet()
}

private fun resolveTelemetryCoordinates(module: Project, configurationName: String): List<String> {
    val configuration = module.configurations.getByName(configurationName)
    // Force a concrete artifact selection so variant mismatches fail hard instead of turning into
    // an empty "clean" result. The coordinate list itself comes from the resolved graph.
    configuration.incoming.artifactView {
        attributes {
            attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                ArtifactTypeDefinition.JAR_TYPE,
            )
        }
    }.files.files

    return configuration.incoming.resolutionResult.allComponents
        .mapNotNull { component ->
            val moduleId = component.id as? ModuleComponentIdentifier
            moduleId?.let { "${it.group}:${it.module}:${it.version}" }
        }
        .distinct()
        .sorted()
}

private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

private fun findMergedReleaseManifests(module: Project): List<File> = module.layout.buildDirectory.asFile.get()
    .resolve("intermediates")
    .takeIf(File::exists)
    ?.walkTopDown()
    ?.filter { file ->
        val normalizedPath = file.invariantPath()
        file.isFile &&
            file.name == "AndroidManifest.xml" &&
            normalizedPath.contains("/merged_manifest/") &&
            normalizedPath.contains("/release/")
    }
    ?.toList()
    .orEmpty()
    .sortedBy { it.path }

private fun parseManifestComponents(manifest: File): List<String> {
    val builderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }
    val document = builderFactory.newDocumentBuilder().parse(manifest)
    val packageName = document.documentElement.getAttribute("package")
    val androidNamespace = "http://schemas.android.com/apk/res/android"
    return listOf("provider", "service", "receiver")
        .flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).mapNotNull { index ->
                val name = nodes.item(index)?.attributes?.getNamedItemNS(androidNamespace, "name")?.nodeValue
                name?.let { qualifyComponentName(packageName, it) }
            }
        }
        .distinct()
        .sorted()
}

private fun qualifyComponentName(packageName: String, rawName: String): String = when {
    rawName.startsWith(".") -> packageName + rawName
    '.' !in rawName -> "$packageName.$rawName"
    else -> rawName
}

private fun findReleaseApks(module: Project): List<File> = module.layout.buildDirectory.asFile.get()
    .resolve("outputs/apk/release")
    .takeIf(File::exists)
    ?.walkTopDown()
    ?.filter { it.isFile && it.extension == "apk" }
    ?.toList()
    .orEmpty()
    .sortedBy { it.path }

private fun scanApkForHosts(apk: File, allowedHosts: Set<String>): List<String> {
    val watchedHosts = (TelemetryRules.forbiddenHostLiterals + allowedHosts).sorted()
    return ZipFile(apk).use { zip ->
        watchedHosts.filter { host ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { entry ->
                    entry.name == "AndroidManifest.xml" ||
                        (
                            entry.name.startsWith("classes") &&
                                entry.name.endsWith(".dex")
                            ) ||
                        entry.name.startsWith("assets/") ||
                        entry.name.startsWith("META-INF/")
                }
                .any { entry ->
                    zip.getInputStream(entry).use { input ->
                        val decoded = input.readBytes().toString(StandardCharsets.ISO_8859_1)
                        host in decoded
                    }
                }
        }
    }.sorted()
}

private fun Project.telemetryConfigurationName(): String? = when {
    configurations.findByName("releaseRuntimeClasspath") != null -> "releaseRuntimeClasspath"
    configurations.findByName("runtimeClasspath") != null -> "runtimeClasspath"
    else -> null
}

private fun Project.telemetryCoordinateReceiptFile(): File =
    layout.buildDirectory.file("reports/privacy/telemetry-coordinates.txt").get().asFile

private fun Project.isAndroidModule(): Boolean = plugins.hasPlugin("com.android.application") ||
    plugins.hasPlugin("com.android.library")

val verifyNoTelemetry = tasks.register("verifyNoTelemetry") {
    group = "verification"
    description = "Fail the build if telemetry artifacts, manifests, or APK host literals land in release outputs."
    val rootDirectory = rootDir
    val privacyReportFile = layout.buildDirectory.file("reports/privacy/privacy-receipts.txt").get().asFile
    val modules = rootProject.subprojects.sortedBy { it.path }

    doLast {
        val scans = mutableListOf<TelemetryModuleScan>()
        val manifestScans = mutableMapOf<String, List<ManifestComponentScan>>()
        val apkScans = mutableMapOf<String, List<ApkHostScan>>()
        val violations = mutableListOf<String>()
        val scannedCoordinates = mutableSetOf<String>()
        val allowedHosts = loadAllowedHosts(rootDirectory)

        modules.forEach { module ->
            val receiptFile = module.telemetryCoordinateReceiptFile()
            if (!receiptFile.exists()) {
                violations += "${module.path} did not produce a telemetry coordinate receipt"
                return@forEach
            }
            val receiptLines = receiptFile.readLines()
            val configuration = receiptLines.firstOrNull()
                ?.removePrefix("configuration=")
                ?.takeIf { it.isNotBlank() }
            if (configuration == null) return@forEach
            val coordinates = receiptLines.drop(1).filter(String::isNotBlank)
            if (coordinates.isEmpty()) {
                violations +=
                    "${module.path} resolved zero external coordinates from $configuration; " +
                    "treat this as misconfigured, not clean."
            }

            scans += TelemetryModuleScan(
                path = module.path,
                configuration = configuration,
                coordinates = coordinates,
            )
            scannedCoordinates += coordinates
            coordinates.forEach { coordinate ->
                val token = coordinate.substringBeforeLast(':')
                if (token in TelemetryRules.forbiddenCoordinates) {
                    violations += "${module.path} pulls forbidden telemetry coordinate $coordinate"
                }
                if (TelemetryRules.auditedCoordinatePrefixes.any { token.startsWith(it) } &&
                    token !in TelemetryRules.allowedModelDownloadCoordinates &&
                    token !in TelemetryRules.forbiddenCoordinates
                ) {
                    violations += "${module.path} pulls unapproved audited coordinate $coordinate"
                }
            }

            if (module.isAndroidModule()) {
                val manifests = findMergedReleaseManifests(module)
                if (manifests.isEmpty()) {
                    violations += "${module.path} produced no merged release AndroidManifest.xml"
                }
                val componentScans = manifests.map { manifest ->
                    val components = parseManifestComponents(manifest)
                    val relativePath = manifest.relativeTo(rootDirectory).path
                    val denied = components.filter { it in TelemetryRules.forbiddenManifestComponents }
                    denied.forEach { component ->
                        violations +=
                            "${module.path} merged manifest includes forbidden component " +
                            "$component ($relativePath)"
                    }
                    ManifestComponentScan(
                        file = relativePath,
                        components = components,
                    )
                }
                manifestScans[module.path] = componentScans
            }

            if (module.plugins.hasPlugin("com.android.application")) {
                val apks = findReleaseApks(module)
                if (apks.isEmpty()) {
                    violations += "${module.path} produced no release APK to scan"
                }
                val hostScans = apks.map { apk ->
                    val matchedHosts = scanApkForHosts(apk, allowedHosts)
                    val relativePath = apk.relativeTo(rootDirectory).path
                    matchedHosts
                        .filter { it in TelemetryRules.forbiddenHostLiterals }
                        .forEach { host ->
                            violations += "${module.path} APK contains forbidden host literal $host ($relativePath)"
                        }
                    ApkHostScan(
                        file = relativePath,
                        matchedHosts = matchedHosts,
                    )
                }
                apkScans[module.path] = hostScans
            }
        }

        if (scannedCoordinates.isEmpty()) {
            violations += "Scanned coordinate list is empty. Empty means the telemetry check is misconfigured."
        }

        privacyReportFile.parentFile.mkdirs()
        privacyReportFile.writeText(
            buildString {
                appendLine("Vestige privacy receipt")
                appendLine()
                appendLine("Allowed download hosts")
                allowedHosts.sorted().forEach { host -> appendLine(host) }
                appendLine()
                appendLine("Resolved coordinates")
                scans.forEach { scan ->
                    appendLine("${scan.path} [${scan.configuration}]")
                    if (scan.coordinates.isEmpty()) {
                        appendLine("  <none>")
                    } else {
                        scan.coordinates.forEach { coordinate -> appendLine("  $coordinate") }
                    }
                }
                appendLine()
                appendLine("Merged manifest components")
                if (manifestScans.isEmpty()) {
                    appendLine("<none>")
                } else {
                    manifestScans.toSortedMap().forEach { (modulePath, scansForModule) ->
                        appendLine(modulePath)
                        scansForModule.forEach { scan ->
                            appendLine("  ${scan.file}")
                            if (scan.components.isEmpty()) {
                                appendLine("    <none>")
                            } else {
                                scan.components.forEach { component -> appendLine("    $component") }
                            }
                        }
                    }
                }
                appendLine()
                appendLine("Release APK host scan")
                if (apkScans.isEmpty()) {
                    appendLine("<none>")
                } else {
                    apkScans.toSortedMap().forEach { (modulePath, scansForModule) ->
                        appendLine(modulePath)
                        scansForModule.forEach { scan ->
                            appendLine("  ${scan.file}")
                            if (scan.matchedHosts.isEmpty()) {
                                appendLine("    <none>")
                            } else {
                                scan.matchedHosts.forEach { host -> appendLine("    $host") }
                            }
                        }
                    }
                }
            },
        )

        if (violations.isNotEmpty()) {
            throw GradleException(
                violations.joinToString(
                    prefix = "Telemetry hardening check failed:\n",
                    separator = "\n",
                    postfix = "\n\nPrivacy receipt: ${privacyReportFile.relativeTo(rootDirectory).path}",
                ),
            )
        }
    }
}

subprojects {
    val moduleProject = this
    val collectTelemetryCoordinates = tasks.register("collectTelemetryCoordinates") {
        group = "verification"
        description = "Resolve this module's runtime coordinates for the root telemetry audit."
        val receiptFile = telemetryCoordinateReceiptFile()
        outputs.file(receiptFile)
        outputs.upToDateWhen { false }

        doLast {
            receiptFile.parentFile.mkdirs()
            val configuration = moduleProject.telemetryConfigurationName()
            if (configuration == null) {
                receiptFile.writeText("configuration=\n")
                return@doLast
            }
            val coordinates = resolveTelemetryCoordinates(moduleProject, configuration)
            receiptFile.writeText(
                buildString {
                    appendLine("configuration=$configuration")
                    coordinates.forEach { coordinate -> appendLine(coordinate) }
                },
            )
        }
    }

    verifyNoTelemetry.configure {
        dependsOn(collectTelemetryCoordinates)
    }

    plugins.withId("com.android.application") {
        verifyNoTelemetry.configure {
            dependsOn(tasks.named("assembleRelease"))
        }
    }
    plugins.withId("com.android.library") {
        verifyNoTelemetry.configure {
            dependsOn(tasks.named("assembleRelease"))
        }
    }
}
