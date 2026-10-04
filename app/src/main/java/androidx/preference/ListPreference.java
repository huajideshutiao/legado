// 兼容 shim: 对齐 keiyoushi extensions-lib 内的 androidx.preference stub 类面 (运行时良性实现)。
// 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference;

import android.content.Context;

@SuppressWarnings("unused")
public class ListPreference extends Preference {

    private CharSequence[] entries;
    private CharSequence[] entryValues;
    private String value;

    public ListPreference(Context context) {
    }

    public CharSequence[] getEntries() {
        return entries;
    }

    public void setEntries(CharSequence[] entries) {
        this.entries = entries;
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

    public CharSequence[] getEntryValues() {
        return entryValues;
    }

    public void setEntryValues(CharSequence[] entryValues) {
        this.entryValues = entryValues;
    }

    public void setValueIndex(int index) {
        if (entryValues != null && index >= 0 && index < entryValues.length) {
            setValue(entryValues[index].toString());
        }
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
