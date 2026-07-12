# Python plugin runtime

## Compatibility target

The legacy surface is frozen against the official exteraGram Python SDK
`1.4.3.10` stubs, plus the public `exteragram-utils` 0.1.x aliases used by
AyuGram plugins. Python files are executed by Chaquopy Python 3.11.

`ui.alert` is the canonical dialog module. `ui.dialog` is an additive alias
requested by this fork. New APIs live under `plugin_api_v2`; legacy names are
not renamed or removed.

## Architecture

1. `ApplicationLoader` evaluates the Java safe-mode marker before starting the
   trusted bundled Python runtime.
2. `plugin_runtime.metadata` parses top-level literal metadata without executing
   plugin code. App/SDK requirements and the plugin id are checked next.
3. Universal pure-Python wheels are resolved and verified before the plugin
   source is published. Source distributions, build scripts and native wheels
   are never executed.
4. Enabled files are loaded after Telegram's account controllers are ready.
   Exactly one `BasePlugin` subclass is constructed inside its owner context
   (metadata and settings are already available from `__init__`), followed by
   `on_plugin_load` and then `AppEvent.START`.
5. Telegram hot paths call the narrow `PluginManager` Java bridge. Ordering,
   `HookResult` chaining and per-plugin exception barriers remain in one Python
   core shared by legacy API and API v2.
6. Settings are persisted as per-plugin JSON and rendered by native Telegram
   fragments. Plugin callbacks stay behind the same fault barrier.
7. Shutdown sends `AppEvent.STOP`, calls `on_plugin_unload`, and removes all
   registrations owned by that plugin.

## Android integration points

| Event | Integration point |
| --- | --- |
| Process/bootstrap | `ApplicationLoader.onCreate` / `postInitApplication` |
| Foreground/background | `ForegroundDetector` |
| Outgoing message | `SendMessagesHelper.sendMessage(SendMessageParams)` |
| All MTProto requests/responses | `ConnectionsManager.sendRequestInternal` |
| Updates container | `MessagesController.processUpdates` |
| Individual update | `MessagesController.processUpdateArray` |
| Plugin manager | `SettingsActivity` -> `PluginsActivity` |

Batch forwards do not use `SendMessageParams`. They are covered by the common
request hook named `TL_messages_forwardMessages` after forum-topic
finalization and before either local placeholders or network serialization.
After the hook succeeds, Telegram creates its normal local placeholders and
then applies the Stars confirmation gate before the request is sent. `CANCEL`
therefore creates no local forwarding messages and does not send the forward
request. One narrow exception is an automatic bot-forum destination: Telegram
must create its server-side topic before the final `top_msg_id` is known, so a
later plugin cancellation can leave that empty topic behind. Seeing the final
topic id and guaranteeing no preflight side effect are mutually exclusive in a
single legacy hook call.
`MODIFY` may change request options, but correlation fields (`to_peer`,
`from_peer`, `id`, `random_id`, schedule, topic and Stars payment terms) must
remain stable; an incompatible result is cancelled instead of desynchronizing
UI, payment confirmation and server.
Albums use the same central request layer.

This request hook is not a protected-content bypass. If Telegram suppresses the
forward action because a chat or message has `noforwards`, no
`TL_messages_forwardMessages` request exists and the hook is not invoked. This
fork already has a separate native `CustomSettings.bypassContentProtection()`
toggle which removes client-side UI restrictions for screenshots, copying,
sharing, saving and forwarding. It is independent of the Python SDK and cannot
weaken server-side permissions; an end-to-end `CHAT_FORWARDS_RESTRICTED` device
test is still required before claiming server forwarding succeeds in every
protected chat.

The audit also found that the current native toggle clears
`messageOwner.noforwards` inside `MessageObject`. Because the same TL object can
later be written to local storage, this may persist the cleared flag and makes
disabling the option unreliable until messages are fetched again. That
pre-existing transport-model mutation is not changed by the plugin runtime and
must be fixed separately by keeping raw server protection immutable and using
a local policy predicate (or an explicit send-as-copy implementation).

## Hook contract

Registrations are ordered by descending priority and then stable registration
order. An override is inactive until `add_hook` or
`add_on_send_message_hook` is called.

| Strategy | Result |
| --- | --- |
| `DEFAULT` | Keep the current value and continue. |
| `CANCEL` | Stop the operation. |
| `MODIFY` | Pass the hook-specific payload to the next plugin. |
| `MODIFY_FINAL` | Accept the payload and stop this plugin chain. |

The `HookResult` positional field order is:

`strategy, request, response, update, updates, error, params`.

## Compatibility matrix

The canonical contract snapshot is the official stable SDK `1.4.3.10`.
Compatibility tests also retain additive AyuGram/exteragram-utils 0.1.x names.
One deliberate introspection difference is `PluginMetadata.requirements`: it is
optional so both the official eight-argument constructor and the historical
seven-argument constructor load unchanged. The official `ui.settings.Text`
signature remains canonical. The typed exteragram-utils positional overload is
also supported once its boolean `accent` argument makes it unambiguous. A call
containing only two positional strings is inherently ambiguous (`subtext` in
the official SDK versus `icon` in 0.1.x), so it follows the official meaning;
the audited real-world fixtures use keyword arguments for icons.

| Surface | Status | Verification |
| --- | --- | --- |
| `.py` / `.plugin`, literal metadata, legacy `__min_version__` | Implemented | AST non-execution, BOM/CRLF, comparator and validation tests |
| `base_plugin` lifecycle and settings persistence | Implemented | Signature snapshot and reference-plugin lifecycle tests |
| send, pre/post request, update/container hooks | Implemented | Priority/order/final/cancel tests and embedded Java-object test |
| `HookResult` / `HookStrategy` | Implemented | Exact field order/defaults; additive `BLOCK`/`CONTINUE` aliases |
| `android_utils`, `file_utils`, `hook_utils` | Implemented for the vertical slice | Import/signature tests; async callbacks use the crash barrier |
| `client_utils` | Implemented, Android oracle pending | Full public call-shape snapshot, strict field validation, text/media helpers and callback barrier; binary-only SDK error wording, concrete return types and wrong-type behavior still require an original-runtime golden test |
| `ui.settings` and sub-settings | Implemented for the vertical slice | Full canonical dataclass call-shape snapshot, typed legacy `Text` overload, callbacks, icons, subpages and native `CustomSetting.Factory` adapter; two-string `Text`, `EditText.mask` and global `link_alias` routing remain ambiguous/unverified |
| `ui.alert`, `ui.dialog`, `ui.bulletin` | Implemented, Android oracle pending | Full call-shape snapshot; native dialogs, buttons, progress and bulletin variants require device verification |
| Markdown/HTML and TL entities | Implemented | Nested format, links, code, custom emoji and UTF-16 offset tests |
| Historical `requests`, `packaging`, `typing_extensions` baseline | Configured | Instrumentation test imports them; final ABI packaging must be verified by the Android build gate |
| `__requirements__` | Implemented | PyPI-only resolver, hash/metadata checks, universal pure wheels and rollback tests |
| API v2 | Implemented as additive facade | Uses the same manager, storage and dispatcher |
| Menu injection into Telegram surfaces | Registry/API retained; UI injection deferred | Not claimed as complete |
| Xposed method hooks and `extera_utils.classes` class proxy | Phase-gated | Raises an explicit compatibility error; not claimed as complete |
| `dev_server` remote debugging | Import-compatible stub only | Server/debugger protocol is not claimed as implemented |

`ui.settings.pyobject_type`, `object_array_type`, `typehelper` and
`pyobject_call_method` remain importable compatibility names. They were
implementation details of the old DexMaker generator and intentionally have no
runtime object until the class-proxy stage.

## Files

- `TMessagesProj/src/main/java/org/telegram/messenger/plugins/`: Java bridge,
  descriptors, safe-mode gate and bridge-level journal.
- `TMessagesProj/src/main/java/com/exteragram/messenger/plugins/`: legacy Java
  package facade used directly by existing Python plugins.
- `TMessagesProj/src/main/python/plugin_runtime/`: AST parser, loader,
  dispatcher, persistence, wheel policy and Android facade.
- `TMessagesProj/src/main/python/*.py`, `ui/`, `extera_utils/`: stable legacy
  import surface.
- `TMessagesProj/src/main/python/plugin_api_v2/`: additive v2 facade over the
  same core.
- `TMessagesProj/src/test/python_plugins/`: host contract/security tests and
  reference plugins.
- `TMessagesProj_AppTests/src/androidTest/`: Chaquopy instrumentation contract.

The Android hot-path edits are limited to `ApplicationLoader`,
`SendMessagesHelper`, `BotForumHelper`, `ConnectionsManager`,
`MessagesController`, `TLObject`, `UItem`, `UniversalAdapter` and
`SettingsActivity`. The legacy facade also changes
`com.exteragram.messenger.plugins.PluginsController`. The new manager screens
are `PluginsActivity` and `PluginSettingsActivity`. APK modules only change
their minimum API to 24, required by the selected Chaquopy runtime.

## Failure policy

Every registered phase-one plugin invocation catches `BaseException`, including
`SystemExit`; this includes raw callbacks round-tripped through the legacy Java
controller. The error journal stores plugin id, callback, traceback and crash
count. At the configured threshold the plugin is disabled and all of its hooks
are removed.
An interrupted startup sets Java safe mode for the next launch. Manual safe
mode starts Telegram without initializing any third-party plugin.

## Test gates

Run host tests with Python 3.11 (these do not build Android):

```text
py -3.11 -X utf8 -m unittest discover -s TMessagesProj/src/test/python_plugins -p "test_*.py" -v
```

Compile Android and run local JVM tests (Windows, from the repository root):

```text
java -Dorg.gradle.workers.max=2 -Xmx4096m -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :TMessagesProj:compileDebugJavaWithJavac :TMessagesProj:testDebugUnitTest --console=plain
```

On a device or emulator, run the embedded-runtime tests:

```text
java -Dorg.gradle.workers.max=2 -Xmx4096m -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :TMessagesProj_AppTests:connectedAfatDebugAndroidTest --console=plain
```

The instrumentation contract imports the historical baseline packages, loads
a synthetic reference plugin stored with the real `.plugin` extension,
exercises constructor ownership, native custom settings,
`MODIFY`/`CANCEL`, request and update hooks with Telegram Java objects, and
verifies Python 3.11.

## Deferred stage: method hooking and class proxies

Xposed-style method replacement and `extera_utils.classes` class proxies require
DexMaker-compatible generated subclasses, constructor phases and Java super
bridges. They are deliberately phase-gated until the loader, lifecycle,
messages, requests, updates, settings and wheel-security suites pass. The
vertical slice does not present a partial dynamic proxy as a compatible
implementation.
