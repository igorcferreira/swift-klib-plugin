package io.github.ttypic.swiftklib.gradle.task

import io.github.ttypic.swiftklib.gradle.CompileTarget
import io.github.ttypic.swiftklib.gradle.EXTENSION_NAME
import io.github.ttypic.swiftklib.gradle.templates.createPackageSwiftContents
import io.github.ttypic.swiftklib.gradle.util.StringReplacingOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject

@DisableCachingByDefault(because = "The generated def file embeds absolute paths, so it can't be relocated")
abstract class CompileSwiftTask @Inject constructor(
    @Input val cinteropName: String,
    @Input val compileTarget: CompileTarget,
    @Input val buildDirectory: String,
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) val pathProperty: Property<File>,
    @Input val packageNameProperty: Property<String>,
    @Optional @Input val minIosProperty: Property<Int>,
    @Optional @Input val minMacosProperty: Property<Int>,
    @Optional @Input val minTvosProperty: Property<Int>,
    @Optional @Input val minWatchosProperty: Property<Int>,
) : DefaultTask() {

    @get:Internal
    internal val targetDir: File
        get() {
            return File(buildDirectory, "${EXTENSION_NAME}/$cinteropName/$compileTarget")
        }

    @get:OutputDirectory
    val swiftBuildDir
        get() = File(targetDir, "swiftBuild")

    @get:OutputFile
    val defFile
        get() = File(targetDir, "$cinteropName.def")

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun produce() {
        val packageName: String = packageNameProperty.get()

        prepareBuildDirectory()
        createPackageSwift()
        val xcodeMajorVersion = readXcodeMajorVersion()
        val (libPath, headerPath) = buildSwift(xcodeMajorVersion)

        createDefFile(
            libPath = libPath,
            headerPath = headerPath,
            packageName = packageName,
            xcodeVersion = xcodeMajorVersion,
        )
    }

    // Defaults track the oldest deployment targets still accepted by current Xcode releases;
    // anything lower is rejected outright ("the range of supported deployment target versions is ...").
    private val minIos get() = minIosProperty.getOrElse(15)
    private val minMacos get() = minMacosProperty.getOrElse(12)
    private val minTvos get() = minTvosProperty.getOrElse(15)
    private val minWatchos get() = minWatchosProperty.getOrElse(8)

    /**
     * Creates build directory or cleans up if it already exists
     * and copies Swift source files to it
     */
    private fun prepareBuildDirectory() {
        val path: File = pathProperty.get()

        if (swiftBuildDir.exists()) swiftBuildDir.deleteRecursively()

        swiftBuildDir.mkdirs()
        path.copyRecursively(buildDir(), true)
    }

    private fun buildDir() =
        File(swiftBuildDir, cinteropName)

    /**
     * Creates `Package.Swift` file for the library
     */
    private fun createPackageSwift() {
        File(swiftBuildDir, "Package.swift")
            .writeText(createPackageSwiftContents(cinteropName))
    }

    private fun buildSwift(xcodeVersion: Int): SwiftBuildResult {
        val usesSwiftBuildEngine = xcodeVersion >= SWIFT_BUILD_ENGINE_XCODE_VERSION
        val sourceFilePathReplacements = mapOf(
            buildDir().absolutePath to pathProperty.get().absolutePath
        )
        val args =
            if (usesSwiftBuildEngine) swiftBuildEngineArgs()
            else nativeBuildSystemArgs(xcodeVersion)

        logger.info("-- Running swift build --")
        logger.info("Working directory: $swiftBuildDir")
        logger.info("xcrun ${args.joinToString(" ")}")

        execOperations.exec {
            it.executable = "xcrun"
            it.workingDir = swiftBuildDir
            it.args = args
            it.standardOutput = StringReplacingOutputStream(
                delegate = System.out,
                replacements = sourceFilePathReplacements
            )
            it.errorOutput = StringReplacingOutputStream(
                delegate = System.err,
                replacements = sourceFilePathReplacements
            )
        }

        return if (usesSwiftBuildEngine) swiftBuildEngineResult()
        else nativeBuildSystemResult()
    }

    /**
     * Build arguments for the Swift Build engine, which SwiftPM defaults to from Xcode 27 onwards.
     *
     * The destination has to be declared through SwiftPM's own `--sdk`/`--triple` options. Passing
     * it via `-Xswiftc` (as [nativeBuildSystemArgs] does) no longer works: Swift Build emits user
     * flags first and appends its own host destination afterwards, so `swiftc` ends up compiling
     * against the macOS SDK while clang keeps the iOS sysroot.
     */
    private fun swiftBuildEngineArgs(): List<String> = listOf(
        "swift",
        "build",
        "-c",
        "release",
        "--sdk",
        readSdkPath(),
        // `--triple` is mutually exclusive with `--arch`, and already carries the architecture.
        "--triple",
        compileTarget.asSwiftcTarget(compileTarget.operatingSystem()),
    )

    private fun nativeBuildSystemArgs(xcodeVersion: Int): List<String> {
        val baseArgs = "swift build --arch ${compileTarget.arch()} -c release".split(" ")

        val xcrunArgs = listOf(
            "-sdk",
            readSdkPath(),
            "-target",
            compileTarget.asSwiftcTarget(compileTarget.operatingSystem()),
        ).asSwiftcArgs()

        val extraArgs = if (xcodeVersion >= 15 && compileTarget in SDKLESS_TARGETS) {
            additionalSysrootArgs()
        } else {
            emptyList()
        }

        return baseArgs + xcrunArgs + extraArgs
    }

    /**
     * Locates the products of a Swift Build engine run, which uses Xcode's output layout rather
     * than the native build system's `.build/<arch>-apple-macosx/release` directory.
     */
    private fun swiftBuildEngineResult(): SwiftBuildResult {
        val outDir = File(swiftBuildDir, ".build/out")

        val productsDir = File(outDir, "Products")
        val libPath = productsDir.walkTopDown().firstOrNull { it.name == libName() }
            ?: throw IllegalStateException("Can't find ${libName()} in $productsDir")

        // The generated Objective-C header and its module map land in a per-platform
        // `GeneratedModuleMaps-<sdk>` directory (plain `GeneratedModuleMaps` when building for the host).
        val intermediatesDir = File(outDir, "Intermediates.noindex")
        val moduleDir = intermediatesDir
            .listFiles { file -> file.isDirectory && file.name.startsWith(GENERATED_MODULE_MAPS_DIR_PREFIX) }
            ?.singleOrNull()
            ?: throw IllegalStateException(
                "Can't find a single $GENERATED_MODULE_MAPS_DIR_PREFIX* directory in $intermediatesDir"
            )

        // Swift Build names the module map after the module, but the clang that cinterop runs only
        // auto-discovers a map called `module.modulemap` inside an include directory.
        File(moduleDir, "$cinteropName.modulemap")
            .copyTo(File(moduleDir, "module.modulemap"), overwrite = true)

        return SwiftBuildResult(
            libPath = libPath,
            headerPath = File(moduleDir, "$cinteropName-Swift.h"),
        )
    }

    private fun nativeBuildSystemResult(): SwiftBuildResult {
        val releaseBuildPath = File(swiftBuildDir, ".build/${compileTarget.arch()}-apple-macosx/release")
        val moduleDir = File(releaseBuildPath, "$cinteropName.build")

        // Newer SwiftPM releases emit the generated header and `module.modulemap` into an `include`
        // subdirectory; older ones write the header directly into the module directory.
        val headerDir = File(moduleDir, "include").takeIf { it.isDirectory } ?: moduleDir

        return SwiftBuildResult(
            libPath = File(releaseBuildPath, libName()),
            headerPath = File(headerDir, "$cinteropName-Swift.h"),
        )
    }

    private fun libName() = "lib${cinteropName}.a"

    /** Workaround for bug in toolchain where the sdk path (via `swiftc -sdk` flag) is not propagated to clang. */
    private fun additionalSysrootArgs(): List<String> =
        listOf(
            "-isysroot",
            readSdkPath(),
        ).asCcArgs()

    private fun List<String>.asSwiftcArgs() = asBuildToolArgs("swiftc")
    private fun List<String>.asCcArgs() = asBuildToolArgs("cc")

    private fun List<String>.asBuildToolArgs(tool: String): List<String> {
        return this.flatMap {
            listOf("-X$tool", it)
        }
    }

    private fun readSdkPath(): String {
        val stdout = ByteArrayOutputStream()

        execOperations.exec {
            it.executable = "xcrun"
            it.args = listOf(
                "--sdk",
                compileTarget.os(),
                "--show-sdk-path",
            )
            it.standardOutput = stdout
        }

        return stdout.toString().trim()
    }

    private fun readXcodeMajorVersion(): Int {
        val stdout = ByteArrayOutputStream()

        execOperations.exec {
            it.executable = "xcodebuild"
            it.args = listOf("-version")
            it.standardOutput = stdout
        }

        val output = stdout.toString().trim()
        val (_, majorVersion) = "Xcode (\\d+)\\..*".toRegex().find(output)?.groupValues
            ?: throw IllegalStateException("Can't find Xcode")

        return majorVersion.toInt()
    }

    private fun readXcodePath(): String {
        val stdout = ByteArrayOutputStream()

        execOperations.exec {
            it.executable = "xcode-select"
            it.args = listOf("--print-path")
            it.standardOutput = stdout
        }

        return stdout.toString().trim()
    }

    /**
     * Generates Def-file for Kotlin/Native Cinterop
     *
     * Note: adds lib-file md5 hash to library in order to automatically
     * invalidate connected cinterop task
     */
    private fun createDefFile(libPath: File, headerPath: File, packageName: String, xcodeVersion: Int) {
        val xcodePath = readXcodePath()

        val linkerPlatformVersion =
            if (xcodeVersion >= 15) compileTarget.linkerPlatformVersionName()
            else compileTarget.linkerMinOsVersionName()

        val modulePath = headerPath.parentFile.absolutePath

        val basicLinkerOpts = listOf(
            "-L/usr/lib/swift",
            "-$linkerPlatformVersion",
            "${minOs(compileTarget)}.0",
            "${minOs(compileTarget)}.0",
            "-L${xcodePath}/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/${compileTarget.os()}"
        )

        val linkerOpts = basicLinkerOpts.joinToString(" ")

        val content = """
            package = $packageName
            language = Objective-C
            modules = $cinteropName

            # md5 ${libPath.md5()}
            staticLibraries = ${libPath.name}
            libraryPaths = "${libPath.parentFile.absolutePath}"

            compilerOpts = -fmodules -I"$modulePath"
            linkerOpts = $linkerOpts
        """.trimIndent()

        logger.info("--- Generated cinterop def file for $cinteropName ---")
        logger.info("--- cinterop def ---")
        logger.info(content)
        logger.info("---/ cinterop def /---")

        defFile.writeText(content)
    }

    private fun CompileTarget.operatingSystem(): String =
        when (this) {
            CompileTarget.iosX64, CompileTarget.iosArm64, CompileTarget.iosSimulatorArm64 -> "ios$minIos"
            CompileTarget.watchosX64, CompileTarget.watchosArm64, CompileTarget.watchosDeviceArm64, CompileTarget.watchosSimulatorArm64 -> "watchos$minWatchos"
            CompileTarget.tvosX64, CompileTarget.tvosArm64, CompileTarget.tvosSimulatorArm64 -> "tvos$minTvos"
            CompileTarget.macosX64, CompileTarget.macosArm64 -> "macosx$minMacos"
        }

    private fun minOs(compileTarget: CompileTarget): Int =
        when (compileTarget) {
            CompileTarget.iosX64, CompileTarget.iosArm64, CompileTarget.iosSimulatorArm64 -> minIos
            CompileTarget.watchosX64, CompileTarget.watchosArm64, CompileTarget.watchosDeviceArm64, CompileTarget.watchosSimulatorArm64 -> minWatchos
            CompileTarget.tvosX64, CompileTarget.tvosArm64, CompileTarget.tvosSimulatorArm64 -> minTvos
            CompileTarget.macosX64, CompileTarget.macosArm64 -> minMacos
        }
}

private data class SwiftBuildResult(
    val libPath: File,
    val headerPath: File,
)

/**
 * First Xcode version whose SwiftPM defaults to the Swift Build engine rather than the (now
 * deprecated) native build system. The two use different command line options and output layouts.
 */
private const val SWIFT_BUILD_ENGINE_XCODE_VERSION = 27

private const val GENERATED_MODULE_MAPS_DIR_PREFIX = "GeneratedModuleMaps"

val SDKLESS_TARGETS = listOf(
    CompileTarget.iosX64,
    CompileTarget.iosArm64,
    CompileTarget.iosSimulatorArm64,
    CompileTarget.watchosArm64,
    CompileTarget.watchosDeviceArm64,
    CompileTarget.watchosX64,
    CompileTarget.watchosSimulatorArm64,
    CompileTarget.tvosArm64,
    CompileTarget.tvosX64,
    CompileTarget.tvosSimulatorArm64,
)

private fun File.md5() = BigInteger(1, MessageDigest.getInstance("MD5").digest(readBytes()))
    .toString(16)
    .padStart(32, '0')
