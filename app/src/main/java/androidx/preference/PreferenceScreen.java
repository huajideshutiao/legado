// 兼容 shim: 对齐 keiyoushi extensions-lib 内的 androidx.preference stub 类面 (运行时良性实现)。
// 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class PreferenceScreen {

    private final List<Preference> preferences = new ArrayList<>();

    public boolean addPreference(Preference preference) {
        return preferences.add(preference);
    }

    public List<Preference> getPreferences() {
        return preferences;
    }
}
