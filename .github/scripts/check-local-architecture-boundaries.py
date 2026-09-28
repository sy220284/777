#!/usr/bin/env python3
"""Ratchet architectural hotspots so new features cannot silently re-centralize the app."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]

LINE_BUDGETS = {
    "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt": 5303,
    "app/src/main/java/com/labteto/dshmobile/data/SessionStore.kt": 2011,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt": 2330,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt": 657,
    "app/src/main/java/com/labteto/dshmobile/automation/HarnessAutomation.kt": 1537,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatInteractionPlanner.kt": 1033,
    "app/src/main/java/com/labteto/dshmobile/local/chat/ChatPersonaGalleryStore.kt": 1053,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessStateContent.kt": 20,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalHarnessUiState.kt": 130,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt": 75,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalTaskRuntime.kt": 20,
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt": 24,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt": 80,
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt": 46,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt": 19,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelHistoryBuffer.kt": 106,
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalPromptContext.kt": 128,
    "app/src/main/java/com/labteto/dshmobile/local/agent/LocalSubagentRunnerFactory.kt": 135,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationWorkCoordinator.kt": 323,
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationChatCoordinator.kt": 525,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessRuntimePolicy.kt": 98,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalHarnessDefaults.kt": 76,
    "app/src/main/java/com/labteto/dshmobile/local/runtime/LocalBundledRuntimeEnvironment.kt": 41,
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalTranscriptRuntime.kt": 67,
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalGroupExecutionModels.kt": 22,
    "app/src/main/java/com/labteto/dshmobile/local/LocalModelConfigurationCoordinator.kt": 172,
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt": 64,
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt": 24,
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalUiRuntime.kt": 17,
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessStreamingComponents.kt": 101,
}

ENGINE_MAX_PUBLIC_METHODS = 0
ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES = 25
AGGREGATE_STATE_MAX_FIELDS = 66
LOCAL_ROOT_MAX_KOTLIN_FILES = 90
PROJECTION_FIELD_BUDGETS = {
    "LocalHarnessSettingsState": 17,
    "LocalHarnessTaskState": 4,
    "LocalHarnessShellState": 10,
}

ENGINE_CONSUMER_ALLOWLIST = {
    "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt",
    "app/src/main/java/com/labteto/dshmobile/local/automation/LocalAutomationRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalSettingsRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalTaskRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/tools/LocalToolsRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/chat/LocalChatRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/model/LocalModelRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/session/LocalSessionRuntime.kt",
    "app/src/main/java/com/labteto/dshmobile/local/work/LocalWorkRuntime.kt",
}

UI_AGGREGATE_STATE_ALLOWLIST = {
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt",
    "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessStateContent.kt",
}


def die(message: str) -> None:
    print(f"[architecture-guard] {message}", file=sys.stderr)
    raise SystemExit(1)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def strip_comments(source: str) -> str:
    source = re.sub(r"/\*[\s\S]*?\*/", "", source)
    return re.sub(r"//.*$", "", source, flags=re.MULTILINE)


for relative, maximum in LINE_BUDGETS.items():
    lines = len(read(relative).splitlines())
    if lines > maximum:
        die(f"{relative} grew to {lines} lines (ratchet: {maximum}); move the new responsibility out")
    print(f"[architecture-guard] {relative}: {lines}/{maximum} lines")

engine_path = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessEngine.kt"
engine = read(engine_path)
constructor = re.search(
    r"class LocalHarnessEngine @Inject constructor\((.*?)\n\) \{",
    engine,
    re.DOTALL,
)
if constructor is None:
    die("unable to locate LocalHarnessEngine constructor")
dependency_count = len(re.findall(r"private val\s+[A-Za-z0-9_]+\s*:", constructor.group(1)))
if dependency_count > ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES:
    die(
        f"LocalHarnessEngine constructor has {dependency_count} dependencies "
        f"(ratchet: {ENGINE_MAX_CONSTRUCTOR_DEPENDENCIES})"
    )

public_method_count = len(
    re.findall(
        r"^    (?:public\s+)?(?:suspend\s+)?fun\s+[A-Za-z0-9_]+\s*\(",
        engine,
        re.MULTILINE,
    )
)
if public_method_count > ENGINE_MAX_PUBLIC_METHODS:
    die(
        f"LocalHarnessEngine exposes {public_method_count} methods "
        f"(ratchet: {ENGINE_MAX_PUBLIC_METHODS}); add capability-specific APIs instead"
    )

models_path = "app/src/main/java/com/labteto/dshmobile/local/LocalHarnessModels.kt"
models = read(models_path)
state_start = models.find("data class LocalHarnessState(")
state_end = models.find("\n)", state_start)
if state_start < 0 or state_end < 0:
    die("unable to locate LocalHarnessState")
state_field_count = len(
    re.findall(r"^\s*val\s+[A-Za-z0-9_]+\s*:", models[state_start:state_end], re.MULTILINE)
)
if state_field_count > AGGREGATE_STATE_MAX_FIELDS:
    die(
        f"LocalHarnessState has {state_field_count} fields "
        f"(ratchet: {AGGREGATE_STATE_MAX_FIELDS}); create a domain/projection state instead"
    )
if "streamingAssistant" in models or "streamingReasoning" in models:
    die("streaming preview must stay outside LocalHarnessState")

projection_path = "app/src/main/java/com/labteto/dshmobile/local/presentation/LocalHarnessUiState.kt"
projection_source = read(projection_path)
for class_name, maximum in PROJECTION_FIELD_BUDGETS.items():
    class_start = projection_source.find(f"data class {class_name}(")
    class_end = projection_source.find("\n)", class_start)
    if class_start < 0 or class_end < 0:
        die(f"unable to locate {class_name}")
    field_count = len(
        re.findall(
            r"^\s*val\s+[A-Za-z0-9_]+\s*:",
            projection_source[class_start:class_end],
            re.MULTILINE,
        )
    )
    if field_count > maximum:
        die(
            f"{class_name} has {field_count} fields (ratchet: {maximum}); "
            "split the projection instead of recreating aggregate state"
        )

screen_path = "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessScreen.kt"
screen_source = read(screen_path)
screen_entry_start = screen_source.find("fun LocalHarnessScreen(")
screen_entry_end = screen_source.find("\n@Composable\nprivate fun GroupChatMemberAvatar", screen_entry_start)
if screen_entry_start < 0 or screen_entry_end < 0:
    die("unable to locate LocalHarnessScreen entry function")
screen_entry = screen_source[screen_entry_start:screen_entry_end]
if "viewModel.state.collectAsStateWithLifecycle()" in screen_entry:
    die("LocalHarnessScreen shell must not directly subscribe to aggregate runtime state")
if "viewModel.shellState.collectAsStateWithLifecycle()" not in screen_entry:
    die("LocalHarnessScreen must subscribe to LocalHarnessShellState")

local_root = ROOT / "app/src/main/java/com/labteto/dshmobile/local"
local_root_files = list(local_root.glob("*.kt"))
if len(local_root_files) > LOCAL_ROOT_MAX_KOTLIN_FILES:
    die(
        f"local/ root has {len(local_root_files)} Kotlin files "
        f"(ratchet: {LOCAL_ROOT_MAX_KOTLIN_FILES}); place new code in a capability package"
    )

main_root = ROOT / "app/src/main/java/com/labteto/dshmobile"
engine_consumers = set()
for path in main_root.rglob("*.kt"):
    text = strip_comments(path.read_text(encoding="utf-8"))
    if re.search(r"\bLocalHarnessEngine\b", text):
        engine_consumers.add(path.relative_to(ROOT).as_posix())
view_model_path = "app/src/main/java/com/labteto/dshmobile/ui/screens/local/LocalHarnessViewModel.kt"
if "LocalHarnessEngine" in strip_comments(read(view_model_path)):
    die("LocalHarnessViewModel must depend on capability runtimes, not LocalHarnessEngine")

unexpected_consumers = sorted(engine_consumers - ENGINE_CONSUMER_ALLOWLIST)
if unexpected_consumers:
    die(
        "new direct LocalHarnessEngine consumer(s): "
        + ", ".join(unexpected_consumers)
        + "; depend on a capability boundary instead"
    )
stale_consumers = sorted(ENGINE_CONSUMER_ALLOWLIST - engine_consumers)
if stale_consumers:
    die("stale LocalHarnessEngine allowlist entries: " + ", ".join(stale_consumers))

ui_root = ROOT / "app/src/main/java/com/labteto/dshmobile/ui"
aggregate_ui_consumers = set()
for path in ui_root.rglob("*.kt"):
    text = strip_comments(path.read_text(encoding="utf-8"))
    if re.search(r"\bLocalHarnessState\b", text):
        aggregate_ui_consumers.add(path.relative_to(ROOT).as_posix())
unexpected_ui_consumers = sorted(aggregate_ui_consumers - UI_AGGREGATE_STATE_ALLOWLIST)
if unexpected_ui_consumers:
    die(
        "UI must consume projected state instead of LocalHarnessState: "
        + ", ".join(unexpected_ui_consumers)
    )

print(
    "[architecture-guard] OK: "
    f"engine deps={dependency_count}, public methods={public_method_count}, "
    f"aggregate fields={state_field_count}, local root files={len(local_root_files)}"
)
