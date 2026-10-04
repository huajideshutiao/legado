// 兼容 shim: 对齐 keiyoushi extensions-lib 内的 androidx.preference stub 类面 (运行时良性实现)。
// 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("unused")
public class MultiSelectListPreference extends DialogPreference {

    private CharSequence[] entries;
    private CharSequence[] entryValues;
    private Set<String> values = new HashSet<>();

    public MultiSelectListPreference(Context context) {
    }

    public void setEntries(CharSequence[] entries) {
        this.entries = entries;
    }

    public CharSequence[] getEntries() {
        return entries;
    }

    public void setEntryValues(CharSequence[] entryValues) {
        this.entryValues = entryValues;
    }

    public CharSequence[] getEntryValues() {
        return entryValues;
    }

    public void setValues(Set<String> values) {
        this.values = values;
    }

    public Set<String> getValues() {
        return values;
    }

    public int findIndexOfValue(String value) {
        if (value == null || entryValues == null) {
            return -1;
        }
        for (int i = entryValues.length - 1; i >= 0; --i) {
            if (value.contentEquals(entryValues[i])) {
                return i;
            }
        }
        return -1;
    }
}
