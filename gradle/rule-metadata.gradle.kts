/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */

val ruleApiVersion = (findProperty("ruleApiVersion") as String?) ?: "2.17.0.5605"

val ruleApiConfiguration = configurations.create("ruleApi") {
  isCanBeConsumed = false
  isCanBeResolved = true
}

dependencies {
  add("ruleApi", "com.sonarsource.rule-api:rule-api:$ruleApiVersion")
}

fun resolveGithubToken(): String {
  val token = System.getenv("GITHUB_TOKEN")?.trim()
  if (!token.isNullOrEmpty()) return token
  val result = ProcessBuilder("gh", "auth", "token")
    .redirectErrorStream(true)
    .start()
  val output = result.inputStream.bufferedReader().readText().trim()
  val exitCode = result.waitFor()
  if (exitCode != 0 || output.isEmpty()) {
    throw GradleException(
      "GITHUB_TOKEN is not set and 'gh auth token' failed. Run 'gh auth login' or export GITHUB_TOKEN."
    )
  }
  return output
}

fun resolveRuleApiJar(): File {
  val override = (findProperty("ruleApiJar") as String?)?.trim()?.takeIf { it.isNotEmpty() }
  return if (override != null) {
    val file = File(override)
    if (!file.isFile) throw GradleException("ruleApiJar '$override' does not exist or is not a file.")
    file
  } else {
    ruleApiConfiguration.singleFile
  }
}

fun runProcess(command: List<String>, workingDir: File, env: Map<String, String>): Pair<Int, String> {
  val process = ProcessBuilder(command)
    .directory(workingDir)
    .redirectErrorStream(true)
    .apply { environment().putAll(env) }
    .start()
  val output = process.inputStream.bufferedReader().readText()
  val exitCode = process.waitFor()
  if (output.isNotBlank()) {
    print(output)
  }
  return exitCode to output
}

tasks.register("generateRuleMetadata") {
  group = "Rules"
  description = "Generate rule metadata using rule-api for specified rule keys."

  doLast {
    val githubToken = resolveGithubToken()

    val ruleKeysProperty = (findProperty("ruleKeys") as String?)?.trim()
    if (ruleKeysProperty.isNullOrEmpty()) {
      throw GradleException("Missing -PruleKeys. Example: -PruleKeys=S1234,S5678")
    }

    val ruleKeys = ruleKeysProperty
      .split(",", " ", "\n", "\t")
      .map { it.trim() }
      .filter { it.isNotEmpty() }

    if (ruleKeys.isEmpty()) {
      throw GradleException("No rule keys found in -PruleKeys. Example: -PruleKeys=S1234,S5678")
    }

    val rspecBranch = (findProperty("rspecBranch") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    val rspecBranchesProperty = (findProperty("rspecBranches") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    val defaultRspecBranches = buildList {
      ruleKeys.forEach { key -> add("rule/add-RSPEC-$key") }
      ruleKeys.forEach { key -> add("rule/$key-add-rust") }
      add("master")
    }
    val rspecBranches = when {
      rspecBranchesProperty != null -> rspecBranchesProperty
        .split(",", " ", "\n", "\t")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
      rspecBranch != null -> listOf(rspecBranch) + defaultRspecBranches
      else -> defaultRspecBranches
    }.distinct()

    val ruleApiJar = resolveRuleApiJar()

    println("Generating rule metadata for ${ruleKeys.size} rule(s).")
    println("Using rule-api: ${ruleApiJar.name}")

    val cacheRoot = layout.buildDirectory.dir("rule-api-cache").get().asFile
    val environment = mapOf(
      "GITHUB_TOKEN" to githubToken,
      "SONAR_USER_HOME" to cacheRoot.absolutePath,
    )

    var lastOutput = ""
    var lastExitCode = 0
    var success = false
    for (branch in rspecBranches) {
      val command = mutableListOf("java", "-jar", ruleApiJar.absolutePath, "generate", "-rule")
      command.addAll(ruleKeys)
      command.addAll(listOf("-branch", branch))
      val (exitCode, output) = runProcess(command, rootDir, environment)
      lastOutput = output
      lastExitCode = exitCode
      val ruleMissing = ruleKeys.any { key ->
        output.contains("Rule '$key' for language 'rust' was not found", ignoreCase = true)
      }
      val severeError = output.contains("SEVERE:", ignoreCase = true)
      if (exitCode == 0 && !ruleMissing && !severeError) {
        success = true
        break
      }
      println("Branch '$branch' did not generate metadata. Trying next branch if available.")
    }
    if (!success) {
      throw GradleException(
        "rule-api failed after trying branches ${rspecBranches.joinToString(", ")} (exit code: $lastExitCode)\n$lastOutput"
      )
    }
  }
}

tasks.register("updateRuleMetadata") {
  group = "Rules"
  description = "Update rule metadata using rule-api."

  doLast {
    val githubToken = resolveGithubToken()
    val ruleApiJar = resolveRuleApiJar()

    println("Updating rule metadata.")
    println("Using rule-api: ${ruleApiJar.name}")

    val cacheRoot = layout.buildDirectory.dir("rule-api-cache").get().asFile
    val environment = mapOf(
      "GITHUB_TOKEN" to githubToken,
      "SONAR_USER_HOME" to cacheRoot.absolutePath,
    )

    val (exitCode, output) = runProcess(listOf("java", "-jar", ruleApiJar.absolutePath, "update"), rootDir, environment)
    if (exitCode != 0) {
      throw GradleException("rule-api failed (exit code: $exitCode)\n$output")
    }
  }
}
