// 兼容 shim: 对齐 keiyoushi extensions-lib 内的 androidx.preference stub 类面 (运行时良性实现)。
// 后续如引入真实 androidx.preference 依赖, 需删除本文件避免重复类。
package androidx.preference;

@SuppressWarnings("unused")
public class TwoStatePreference extends Preference {

    private boolean checked;
    private CharSequence summaryOn;
    private CharSequence summaryOff;
    private boolean disableDependentsState;

    public TwoStatePreference() {
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public CharSequence getSummaryOn() {
        return summaryOn;
    }

    public void setSummaryOn(CharSequence summaryOn) {
        this.summaryOn = summaryOn;
    }

    public CharSequence getSummaryOff() {
        return summaryOff;
    }

    public void setSummaryOff(CharSequence summaryOff) {
        this.summaryOff = summaryOff;
    }

    public boolean getDisableDependentsState() {
        return disableDependentsState;
    }

    public void setDisableDependentsState(boolean disableDependentsState) {
        this.disableDependentsState = disableDependentsState;
    }
}
