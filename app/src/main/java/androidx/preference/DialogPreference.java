// 兼容 shim: 对齐 keiyoushi extensions-lib 内的 androidx.preference stub 类面 (运行时良性实现)。
// 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference;

@SuppressWarnings("unused")
public abstract class DialogPreference extends Preference {

    private CharSequence dialogTitle;
    private CharSequence dialogMessage;

    public CharSequence getDialogTitle() {
        return dialogTitle;
    }

    public void setDialogTitle(CharSequence dialogTitle) {
        this.dialogTitle = dialogTitle;
    }

    public CharSequence getDialogMessage() {
        return dialogMessage;
    }

    public void setDialogMessage(CharSequence dialogMessage) {
        this.dialogMessage = dialogMessage;
    }
}
