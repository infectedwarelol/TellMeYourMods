#!/usr/bin/env bash
set -uo pipefail

cd "$(dirname "$0")" || exit 1

java_sources="src/main/java"
plugin_descriptor="src/main/resources/plugin.yml"
errors=0
warnings=0

error() {
    echo "error: $*"
    errors=$((errors + 1))
}

warning() {
    echo "warning: $*"
    warnings=$((warnings + 1))
}

build_log="$(mktemp)"
trap 'rm -f "$build_log"' EXIT

echo "==> Building"
if ! ./gradlew clean build --console=plain >"$build_log" 2>&1; then
    error "gradle build failed: $(grep -m1 -E 'error:|FAILURE|What went wrong' "$build_log" || echo 'see gradle output')"
fi
while IFS= read -r line; do
    warning "javac: $line"
done < <(grep -E '(\.java:[0-9]+: )?warning: ' "$build_log")
for flag in -Xlint:all -Werror; do
    if ! grep -qF "\"$flag\"" build.gradle.kts; then
        error "build.gradle.kts does not compile with $flag"
    fi
done

echo "==> Running IntelliJ inspections"
if ! command -v idea >/dev/null 2>&1; then
    warning "IntelliJ IDEA (idea) is not installed; code inspections were skipped"
else
    idea_home="$(mktemp -d)"
    trap 'rm -f "$build_log"; rm -rf "$idea_home"' EXIT
    mkdir -p "$idea_home/config/options" "$idea_home/system" "$idea_home/log" "$idea_home/out"
    for user_config in "$HOME"/.config/JetBrains/Idea*/options; do
        for option in jdk.table.xml path.macros.xml trusted-paths.xml; do
            [[ -f "$user_config/$option" ]] && cp "$user_config/$option" "$idea_home/config/options/"
        done
    done
    cat >"$idea_home/idea.properties" <<PROPERTIES
idea.config.path=$idea_home/config
idea.system.path=$idea_home/system
idea.log.path=$idea_home/log
PROPERTIES
    if ! IDEA_PROPERTIES="$idea_home/idea.properties" timeout 1800 idea inspect "$PWD" "$PWD/inspection-profile.xml" \
            "$idea_home/out" -d "$PWD/$java_sources" -format json -v0 >"$idea_home/inspect.log" 2>&1; then
        error "IntelliJ inspections did not finish: $(tail -n1 "$idea_home/inspect.log")"
    fi
    while IFS=$'\t' read -r severity location description; do
        if [[ "$severity" == "ERROR" ]]; then
            error "idea: $location $description"
        else
            warning "idea: $location $description"
        fi
    done < <(python3 - "$idea_home/out" <<'PYTHON'
import json, pathlib, re, sys
for report in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    data = json.loads(report.read_text())
    problems = data.get("problems", []) if isinstance(data, dict) else data
    for problem in problems:
        location = re.sub(r"^.*?/src/", "src/", problem.get("file", "")) + ":" + str(problem.get("line", ""))
        description = re.sub(r"<[^>]+>|\s+", " ", problem.get("description", "")).strip()
        print(f"{problem.get('severity', 'WARNING')}\t{location}\t[{report.stem}] {description}")
PYTHON
)
fi

echo "==> Checking jar"
version="$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)"
jar="build/libs/TellMeYourMods-${version}.jar"
if [[ -z "$version" ]]; then
    error "build.gradle.kts has no version"
elif [[ ! -f "$jar" ]]; then
    error "$jar was not built"
else
    jar_version="$(unzip -p "$jar" plugin.yml 2>/dev/null | sed -n "s/^version: '\(.*\)'$/\1/p")"
    if [[ "$jar_version" != "$version" ]]; then
        error "plugin.yml version '$jar_version' does not match build.gradle.kts version '$version'"
    fi
fi

echo "==> Checking commands"
declared_commands="$(awk '/^commands:/{inside=1; next} /^[a-z]/{inside=0} inside && /^  [a-z0-9_-]+:$/{gsub(/[ :]/, ""); print}' "$plugin_descriptor")"
for command in $declared_commands; do
    if ! grep -rqF "\"$command\"" "$java_sources"; then
        error "/$command is declared in plugin.yml but never registered in Java"
    fi
done

echo "==> Checking permissions"
declared_permissions="$(awk '/^permissions:/{inside=1; next} inside && /^  [a-z0-9_.*-]+:$/{gsub(/[ :]/, ""); print}' "$plugin_descriptor")"
while IFS= read -r permission; do
    if ! grep -qxF "$permission" <<<"$declared_permissions"; then
        warning "permission $permission is used in Java but not declared in plugin.yml"
    fi
done < <(grep -rhoE '"tellmeyourmods\.[a-z0-9_.-]*[a-z0-9_-]"' "$java_sources" | tr -d '"' | sort -u)

echo "==> Checking sources"
while IFS= read -r line; do
    error "comment: $line"
done < <(grep -rnE '^\s*(//|/\*|\*|#)' src build.gradle.kts settings.gradle.kts gradle.properties)
while IFS= read -r line; do
    warning "unfinished: $line"
done < <(grep -rnE 'TODO|FIXME|XXX' src)
while IFS= read -r line; do
    error "em dash: $line"
done < <(grep -rnP '\x{2014}' src build.gradle.kts inspect.sh)

echo "==> $errors error(s), $warnings warning(s)"
[[ $errors -eq 0 && $warnings -eq 0 ]]
