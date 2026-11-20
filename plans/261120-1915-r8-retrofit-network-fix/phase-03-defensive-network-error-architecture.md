# Phase 03: Defensive Network Error Architecture & User-Friendly Translation

**Status:** ✅ Completed  
**Dependencies:** Phase 01, Phase 02  

## Objective
Eliminate the display of raw Java exceptions, technical stack traces, and internal class names (such as `java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType`) on the user-facing UI. Build a centralized, resilient `NetworkErrorResolver` that maps technical exceptions to clear, polite Vietnamese explanations, and automatically coordinates cookie re-authentication via `HostingVerifier` when sessions expire.

## Scope
- Create `com.skul9x.doctruyen.network.NetworkErrorResolver` to categorize and translate network errors into user-friendly messages.
- Refactor `MainActivity.kt` and `StoryDetailActivity.kt` to route all network exception handling through `NetworkErrorResolver`.
- Enhance the automatic re-authentication flow when receiving expired cookies (e.g., HTML response from free.nf or 403 Forbidden).
- Ensure error states in UI display clear guidance ("Kiểm tra kết nối mạng", "Phiên kết nối hết hạn, đang tự động kết nối lại...", "Không thể tải truyện lúc này") with a functioning "Thử lại" action.

## Requirements
### Functional
1. **User-Friendly Error Translation**:
   - `UnknownHostException` / `ConnectException` $\to$ "Không thể kết nối Internet. Vui lòng kiểm tra lại Wifi hoặc 4G."
   - `SocketTimeoutException` $\to$ "Máy chủ phản hồi quá lâu. Vui lòng thử lại sau giây lát."
   - `MalformedJsonException` / `JsonSyntaxException` / HTML content response $\to$ "Phiên truy cập đã hết hạn. Đang làm mới dữ liệu..."
   - `ClassCastException` / Unexpected system error $\to$ "Có lỗi xảy ra khi xử lý dữ liệu. Vui lòng nhấn Thử lại."
2. **Automated Session Recovery**:
   - When an authentication or HTML cookie error is detected and `RetrofitClient.cookie` is present, `NetworkErrorResolver.shouldRefreshAuth(throwable)` returns true.
   - The UI automatically clears the stale cookie, triggers `HostingVerifier.verify()`, and re-invokes the pending network call upon success.
3. **Never Expose Internal Reflection Names**:
   - Technical class paths (e.g. `java.lang.reflect.*`, `okhttp3.*`, `retrofit2.*`) must never appear in `errorText.text` or Toast alerts.

### Non-Functional
- Error resolution must execute synchronously with zero allocation overhead.
- No memory leaks through static context retention.

## How to Implement
1. **Create `NetworkErrorResolver.kt`**:
   - Define sealed class or object `NetworkErrorResolver` in `com.skul9x.doctruyen.network`:
     ```kotlin
     object NetworkErrorResolver {
         data class ResolvedError(
             val userMessage: String,
             val isAuthError: Boolean,
             val canRetry: Boolean
         )

         fun resolve(throwable: Throwable?, context: Context): ResolvedError {
             if (throwable == null) {
                 return ResolvedError(context.getString(R.string.error_unknown), false, true)
             }
             // Detect connection issues
             if (throwable is java.net.UnknownHostException || throwable is java.net.ConnectException) {
                 return ResolvedError(context.getString(R.string.error_no_internet), false, true)
             }
             if (throwable is java.net.SocketTimeoutException) {
                 return ResolvedError(context.getString(R.string.error_timeout), false, true)
             }
             // Detect auth / cookie expiration
             val msg = throwable.message ?: ""
             val isAuth = throwable is com.google.gson.stream.MalformedJsonException ||
                          msg.contains("Forbidden", ignoreCase = true) ||
                          msg.contains("403") ||
                          msg.contains("html", ignoreCase = true)

             if (isAuth) {
                 return ResolvedError("Phiên kết nối hết hạn, đang xác thực lại...", true, true)
             }

             // Graceful fallback for unexpected or reflection errors
             return ResolvedError("Không thể tải dữ liệu lúc này. Vui lòng thử lại.", false, true)
         }
     }
     ```
2. **Refactor `MainActivity.kt`**:
   - Replace manual `catch (e: Exception)` handling in `loadStories()` and `loadRandomStory()` with `NetworkErrorResolver.resolve(e, this)`.
   - Update `showError(message)` to accept resolved messages.
   - Automate `HostingVerifier.verify()` re-triggering upon `isAuthError`.
3. **Refactor `StoryDetailActivity.kt`**:
   - Route `loadStoryContent()` exceptions through `NetworkErrorResolver` for uniform error styling.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/network/NetworkErrorResolver.kt` - Create centralized error resolver.
- `app/src/main/java/com/skul9x/doctruyen/MainActivity.kt` - Integrate `NetworkErrorResolver` in story loading routines.
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` - Integrate `NetworkErrorResolver` in story content loading.
- `app/src/main/res/values/strings.xml` - Add friendly localized error string resources if not already present.

## Dependencies
- Phase 01, Phase 02.

## Constraints
- Must not alter normal success response handling or playback flows.
- Must remain compatible with Android API 24+.

## Acceptance Criteria
- Technical stack traces and `ClassCastException` strings are completely shielded from user UI.
- Connection failures, timeouts, and cookie expirations display clear Vietnamese messages.
- Stale cookies trigger silent re-authentication with `HostingVerifier`.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/network/NetworkErrorResolverTest.kt`
- **Verification Strategy:**
  1. `testResolveUnknownHostException`: Verifies that `UnknownHostException` translates to an internet connectivity message.
  2. `testResolveTimeoutException`: Verifies that `SocketTimeoutException` translates to a timeout message.
  3. `testResolveAuthAndHtmlParsingError`: Verifies that `MalformedJsonException` and "403 Forbidden" are identified with `isAuthError = true`.
  4. `testResolveReflectionClassCastException`: Verifies that `ClassCastException` does not leak Java package names and returns a polite retry prompt.
  5. `testNullOrEmptyThrowableHandling`: Verifies safe fallback when throwable or message is null.

