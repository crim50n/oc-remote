# OC REMOTE KNOWLEDGE BASE

**Generated:** 2026-03-13 01:32 UTC  
**Commit:** 17c6794  
**Branch:** master

Android client for OpenCode servers. Kotlin/Compose app with clean architecture.

## Build & Test Commands

### Build

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing config)
./gradlew assembleRelease

# Install debug build on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clean build
./gradlew clean
```

### Test

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "dev.minios.ocremote.SomeTest"

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run all checks (lint + test)
./gradlew check
```

**Note**: Test infrastructure is configured (JUnit 4, Espresso, Compose UI testing) but no test files currently exist.

### Lint & Code Quality

```bash
# Run Android lint
./gradlew lint

# Check for dependency updates
./gradlew dependencyUpdates
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Add API endpoint | `data/api/OpenCodeApi.kt` | Stateless client, takes `ServerConnection` per call (50+ endpoints) |
| Add SSE event type | `data/api/SseClient.kt` + `domain/model/SseEvent.kt` | Parse in SseClient, handle in EventReducer |
| Add screen | `ui/screens/{name}/` | Create Screen.kt + ViewModel.kt, register in NavGraph.kt |
| Modify state management | `data/repository/EventReducer.kt` | Central SSE event processor, maintains reactive state |
| Add setting | `data/repository/SettingsRepository.kt` + `ui/screens/settings/` | DataStore-backed, expose as Flow |
| Add dependency | `app/build.gradle.kts` | Kotlin DSL, Hilt modules in `di/` |
| Localization | `app/src/main/res/values-XX/strings.xml` | 15 locales managed via `lokit` CLI |
| Build/release | `.github/workflows/release.yml` | Triggered on `v*` tags, decodes keystore from secrets |
| Cross-cutting state | `data/repository/EventReducer.kt` | Central SSE event processor, maintains reactive state for all servers |
| DI configuration | `di/NetworkModule.kt` | Provides singleton HttpClient, Json serializer, DataStore |
| Shared UI components | `ui/components/ProviderIcon.kt` | Reusable composables with theme-aware rendering |

## Project Structure

```
app/src/main/kotlin/dev/minios/ocremote/
├── domain/model/        # Domain entities (Message, Session, ServerConfig)
├── data/api/            # API clients (OpenCodeApi, SseClient)
├── data/repository/     # Repository pattern (ServerRepository, EventReducer)
├── ui/screens/          # Screen composables organized by feature
│   ├── chat/            # Chat screen + ViewModel
│   ├── home/            # Home screen + server management
│   ├── sessions/        # Session list
│   ├── settings/        # Settings screen
│   └── server/          # Server configuration screens
├── ui/components/       # Reusable UI components
├── ui/theme/            # Material 3 theme (Color, Type, Theme)
├── ui/navigation/       # Navigation graph
├── di/                  # Hilt dependency injection modules
└── service/             # Background services (connection service)
```

## Code Style Guidelines

### Language & Tooling

- **Kotlin 2.0.21** with official code style (`kotlin.code.style=official`)
- **Target**: Android 8.0+ (API 26), compile SDK 34
- **Build**: Gradle with Kotlin DSL (.kts files)
- **JVM Target**: Java 17

### Architecture Patterns

- **Clean Architecture**: Separate domain/data/ui layers
- **Dependency Injection**: Hilt (Dagger 2.51) with `@Inject`, `@Singleton`, `@HiltViewModel`
- **State Management**: Kotlin Flow (`StateFlow`, `MutableStateFlow`, `SharedFlow`)
- **UI Framework**: Jetpack Compose with Material 3
- **Async**: Coroutines with `viewModelScope` and `lifecycleScope`

### Naming Conventions

- **Files/Classes**: PascalCase matching class name (`ChatViewModel.kt`, `OpenCodeApi.kt`)
- **Functions/Variables**: camelCase (`sendMessage`, `isLoading`)
- **Constants**: UPPER_SNAKE_CASE (`private const val TAG = "ChatViewModel"`)
- **Private StateFlow fields**: Underscore prefix (`_uiState`, `_messages`)
- **Exposed StateFlow**: Public without underscore (`val uiState: StateFlow<...>`)
- **Sealed class variants**: PascalCase (`Message.User`, `Message.Assistant`)
- **Composables**: PascalCase function names (`@Composable fun ChatScreen()`)

### Import Organization

Absolute imports only, no wildcards. Organize by source:

```kotlin
// 1. Android framework
import android.util.Log
import android.content.Intent

// 2. AndroidX libraries
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel

// 3. Project packages (dev.minios.ocremote)
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.data.api.OpenCodeApi

// 4. Third-party libraries (alphabetical)
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.StateFlow

// 5. Java standard library
import javax.inject.Inject
import java.util.UUID
```

### Type System

- **Data classes** for DTOs and immutable state
- **Sealed classes** for type-safe polymorphism (e.g., `Message`, `Part`, `SseEvent`)
- **Nullable types** explicit with `?` — avoid platform types
- **Type inference** preferred when obvious, explicit when clarity helps

### Serialization

- **kotlinx.serialization** with `@Serializable` annotation
- **Field mapping**: `@SerialName("sessionID")` for API field names
- **Polymorphic types**: Custom serializers for sealed classes (see `MessageSerializer`)
- **JSON config**: Lenient parsing with `ignoreUnknownKeys = true`

### Error Handling

- **Try-catch** with logging for expected failures
- **Log levels**: `Log.e()` for errors, `Log.d()` for debug (check `BuildConfig.DEBUG`)
- **Graceful fallbacks**: Provide default values or empty states
- **runCatching**: Use for safe operations that may fail
- **Result type**: Use `Result<T>` for operations that can fail

### State Management

- **ViewModel**: Hold UI state in `StateFlow`
- **Private mutable, public immutable**: `_uiState` (MutableStateFlow) + `uiState` (StateFlow)
- **State updates**: Use `.update { }` for atomic modifications
- **Side effects**: Launch in `viewModelScope` or `lifecycleScope`

### Compose UI

- **Composable functions**: PascalCase, `@Composable` annotation
- **State hoisting**: Pass state and callbacks as parameters
- **Modifiers**: Chain in logical order (size → padding → background → click)
- **Preview**: Add `@Preview` for development
- **Remember**: Use `remember` for computed values, `rememberSaveable` for config changes

### Dependency Injection (Hilt)

- **Application**: `@HiltAndroidApp` on Application class
- **Activity**: `@AndroidEntryPoint` on Activity
- **ViewModel**: `@HiltViewModel` + `@Inject constructor()`
- **Modules**: `@Module` + `@InstallIn` for providing dependencies
- **Singletons**: `@Singleton` for app-scoped instances

### Coroutines

- **Scope**: Use `viewModelScope` in ViewModels, `lifecycleScope` in Activities
- **Suspend functions**: Mark async operations with `suspend`
- **Flow collection**: Use `.collectAsState()` in Compose, `.collect {}` elsewhere
- **Error handling**: Wrap in try-catch or use `.catch {}` operator

### Logging

- **TAG constant**: `private const val TAG = "ClassName"`
- **Debug logs**: Wrap in `if (BuildConfig.DEBUG)` for performance
- **Error logs**: Always include exception and context

## Common Patterns

**Repository Pattern:**
- Repositories handle data operations and expose Flow for reactive updates
- `@Singleton` with `@Inject constructor(private val dataStore: DataStore<Preferences>)`
- Expose `val servers: Flow<List<ServerConfig>>` for reactive access
- Suspend functions for write operations

**API Client Pattern:**
- API clients are stateless and take `ServerConnection` for each call
- `@Singleton` with `@Inject constructor(private val httpClient: HttpClient)`
- Each endpoint: `suspend fun getSessions(conn: ServerConnection): List<Session>`
- Auth header passed per-request via `conn.authHeader`

**Screen + ViewModel Pattern:**
- Each screen has ViewModel with `data class {Name}UiState`
- ViewModel: `@HiltViewModel class {Name}ViewModel @Inject constructor(...)`
- Private `_uiState: MutableStateFlow`, public `uiState: StateFlow`
- Screen: `@Composable fun {Name}Screen(viewModel: {Name}ViewModel = hiltViewModel())`
- Collect state: `val uiState by viewModel.uiState.collectAsState()`

## Dependencies

### Core
- Kotlin 2.0.21
- AndroidX Core KTX 1.13.1
- Jetpack Compose (BOM 2024.12.01)
- Material 3

### Networking
- Ktor Client 2.3.11 (OkHttp engine for SSE support)
- kotlinx.serialization 1.7.1

### DI & Architecture
- Hilt 2.51
- Navigation Compose 2.7.7
- Lifecycle Runtime KTX 2.8.4

### UI
- Compose Material 3
- Coil 2.6.0 (image loading)
- Markdown Renderer 0.28.0 (multiplatform-markdown-renderer)

### Storage
- DataStore Preferences 1.1.1

### Testing (configured but no tests exist)
- JUnit 4.13.2
- Espresso 3.6.1
- Compose UI Test
- Coroutines Test 1.8.1

## Development Workflow

1. **Feature branches**: Create from main for new features
2. **Build locally**: `./gradlew assembleDebug` before committing
3. **Test on device**: Install and verify on physical device or emulator
4. **Code review**: Ensure style consistency and architecture patterns
5. **Release**: Update version in `app/build.gradle.kts`, create tag, CI builds APK

## Release Process

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`
2. Create release notes file: `RELEASE_NOTES_<version>.md` (optional, fallback to auto-generated)
3. Commit and push
4. Create tag: `git tag v<version> && git push origin v<version>`
5. GitHub Actions builds and uploads APK to release

## ANTI-PATTERNS (THIS PROJECT)

**Security:**
- `WebViewScreen.kt:145` uses `MIXED_CONTENT_ALWAYS_ALLOW` — allows loading insecure HTTP content on HTTPS pages, creating MITM vulnerability. Use `MIXED_CONTENT_NEVER_ALLOW` or `MIXED_CONTENT_COMPATIBILITY_MODE` instead.

**From KNOWN_ISSUES.md:**
- Local runtime start fails when Termux `allow-external-apps` is disabled. User must enable in `~/.termux/termux.properties` and fully restart Termux.

**CI/CD:**
- Version extraction uses fragile sed parsing instead of Gradle API
- No automated testing in release workflow

**Testing:**
- Test infrastructure configured (JUnit 4, Espresso, Compose UI testing) but no test files exist

**Code Complexity:**
- `ChatScreen.kt` is 6977 lines with 68 composable functions — needs decomposition into feature-based sub-components (ChatMessageList, ChatInputField, ChatToolbar, ChatTerminalMode)

## Notes

**Multi-Server Design:**
- `ServerConnection` holds resolved URL + auth header, created via `ServerConnection.from()`
- API client is stateless, no connection pooling per server
- EventReducer tracks sessions per server, enables concurrent multi-server support

**SSE Streaming:**
- OkHttp engine for true byte-level streaming (Ktor's ContentNegotiation buffers entire response)
- Heartbeat timeout 40s, auto-reconnection delegated to caller
- 20+ event types: session status, messages, permissions, questions, todos, VCS, LSP

**Data Persistence:**
- ServerRepository (DataStore): saved server configs with health status
- SettingsRepository (DataStore): 30+ app settings
- DraftRepository (file-based JSON): per-session message drafts with attachments

**API Integration:**
- 50+ REST endpoints (sessions, messages, permissions, providers, config, files, PTY)
- Stateless design: takes `ServerConnection` per call for multi-server support
- Basic auth via Base64 username:password

**Build & Release:**
- Localization: 15 locales managed with `lokit` tool
- ProGuard: Enabled for release builds with custom rules in `proguard-rules.pro`
- Signing: Release builds require `app/keystore/signing.properties` (not in repo)
- Min SDK: API 26 (Android 8.0) for modern features
- Large heap: Enabled in manifest for handling large sessions
