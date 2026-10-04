// 兼容 shim: 对齐 keiyoushi extensions-lib 内的 androidx.preference stub 类面 (运行时良性实现)。
// 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference;

import android.content.Context;
import android.widget.EditText;

@SuppressWarnings("unused")
public class EditTextPreference extends DialogPreference {

    private String text;

    public EditTextPreference(Context context) {
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setOnBindEditTextListener(OnBindEditTextListener onBindEditTextListener) {
    }

    public interface OnBindEditTextListener {
        void onBindEditText(EditText editText);
    }
}
