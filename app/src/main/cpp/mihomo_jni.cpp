#include <jni.h>
#include <cstdlib>

extern "C" {
int MihomoStart(char* config, char* home, int tun_fd, char* dns_override, char* overrides_json);
void MihomoStop();
char* MihomoLastError();
char* MihomoVersion();
char* MihomoTraffic();
char* MihomoProxies();
int MihomoSelectProxy(char* group, char* name);
char* MihomoProxyDelay(char* name, char* url, int timeout_ms);
char* MihomoValidateDns(char* dns_yaml);
char* MihomoGroupOrder();
char* MihomoRules();
char* MihomoRuntimeInfo();
int MihomoSetMode(char* mode);
char* MihomoTrafficByProxy();
char* MihomoConnections();
int MihomoCloseConnections();
int MihomoCloseConnection(char* id);
}

// Scoped owner for GetStringUTFChars. GetStringUTFChars can fail under memory
// pressure and return nullptr with an OutOfMemoryError pending; handing that
// null into the cgo entry points would crash the process, so callers check
// get() and return early — the pending exception then propagates as normal.
class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring str)
            : env_(env), str_(str), chars_(env->GetStringUTFChars(str, nullptr)) {}
    ~UtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(str_, chars_);
    }
    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;
    const char* get() const { return chars_; }
private:
    JNIEnv* env_;
    jstring str_;
    const char* chars_;
};

extern "C" JNIEXPORT jint JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeStart(
        JNIEnv* env, jobject /* thiz */, jstring config, jstring home, jint tun_fd,
        jstring dns_override, jstring overrides_json) {
    UtfChars config_chars(env, config);
    UtfChars home_chars(env, home);
    UtfChars dns_chars(env, dns_override);
    UtfChars overrides_chars(env, overrides_json);
    if (!config_chars.get() || !home_chars.get() || !dns_chars.get() || !overrides_chars.get()) {
        return -1;
    }
    return MihomoStart(
            const_cast<char*>(config_chars.get()), const_cast<char*>(home_chars.get()), tun_fd,
            const_cast<char*>(dns_chars.get()), const_cast<char*>(overrides_chars.get()));
}

extern "C" JNIEXPORT void JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeStop(JNIEnv* /* env */, jobject /* thiz */) {
    MihomoStop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeLastError(JNIEnv* env, jobject /* thiz */) {
    char* error = MihomoLastError();
    jstring result = env->NewStringUTF(error == nullptr ? "" : error);
    std::free(error);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeVersion(JNIEnv* env, jobject /* thiz */) {
    char* version = MihomoVersion();
    jstring result = env->NewStringUTF(version == nullptr ? "unknown" : version);
    std::free(version);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeTraffic(JNIEnv* env, jobject /* thiz */) {
    char* traffic = MihomoTraffic();
    jstring result = env->NewStringUTF(traffic == nullptr ? "{}" : traffic);
    std::free(traffic);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeProxies(JNIEnv* env, jobject /* thiz */) {
    char* proxies = MihomoProxies();
    jstring result = env->NewStringUTF(proxies == nullptr ? "{}" : proxies);
    std::free(proxies);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeSelectProxy(
        JNIEnv* env, jobject /* thiz */, jstring group, jstring name) {
    UtfChars group_chars(env, group);
    UtfChars name_chars(env, name);
    if (!group_chars.get() || !name_chars.get()) {
        return -1;
    }
    return MihomoSelectProxy(
            const_cast<char*>(group_chars.get()), const_cast<char*>(name_chars.get()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeProxyDelay(
        JNIEnv* env, jobject /* thiz */, jstring name, jstring url, jint timeout_ms) {
    UtfChars name_chars(env, name);
    UtfChars url_chars(env, url);
    if (!name_chars.get() || !url_chars.get()) {
        return env->NewStringUTF("{\"error\":\"oom\"}");
    }
    char* delay = MihomoProxyDelay(
            const_cast<char*>(name_chars.get()), const_cast<char*>(url_chars.get()), timeout_ms);
    jstring result = env->NewStringUTF(delay == nullptr ? "{\"error\":\"null\"}" : delay);
    std::free(delay);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeValidateDns(
        JNIEnv* env, jobject /* thiz */, jstring dns_yaml) {
    UtfChars dns_chars(env, dns_yaml);
    if (!dns_chars.get()) {
        return env->NewStringUTF("out of memory");
    }
    char* err = MihomoValidateDns(const_cast<char*>(dns_chars.get()));
    jstring result = env->NewStringUTF(err == nullptr ? "" : err);
    std::free(err);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeGroupOrder(JNIEnv* env, jobject /* thiz */) {
    char* order = MihomoGroupOrder();
    jstring result = env->NewStringUTF(order == nullptr ? "[]" : order);
    std::free(order);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeRules(JNIEnv* env, jobject /* thiz */) {
    char* rules = MihomoRules();
    jstring result = env->NewStringUTF(rules == nullptr ? "[]" : rules);
    std::free(rules);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeRuntimeInfo(JNIEnv* env, jobject /* thiz */) {
    char* info = MihomoRuntimeInfo();
    jstring result = env->NewStringUTF(info == nullptr ? "{}" : info);
    std::free(info);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeSetMode(
        JNIEnv* env, jobject /* thiz */, jstring mode) {
    UtfChars mode_chars(env, mode);
    if (!mode_chars.get()) {
        return -1;
    }
    return MihomoSetMode(const_cast<char*>(mode_chars.get()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeTrafficByProxy(JNIEnv* env, jobject /* thiz */) {
    char* traffic = MihomoTrafficByProxy();
    jstring result = env->NewStringUTF(traffic == nullptr ? "{}" : traffic);
    std::free(traffic);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeConnections(JNIEnv* env, jobject /* thiz */) {
    char* connections = MihomoConnections();
    jstring result = env->NewStringUTF(connections == nullptr ? "{}" : connections);
    std::free(connections);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeCloseConnections(JNIEnv* /* env */, jobject /* thiz */) {
    return MihomoCloseConnections();
}

extern "C" JNIEXPORT jint JNICALL
Java_top_uwu_mikubox_core_MihomoCore_nativeCloseConnection(
        JNIEnv* env, jobject /* thiz */, jstring id) {
    UtfChars id_chars(env, id);
    if (!id_chars.get()) {
        return -1;
    }
    return MihomoCloseConnection(const_cast<char*>(id_chars.get()));
}
