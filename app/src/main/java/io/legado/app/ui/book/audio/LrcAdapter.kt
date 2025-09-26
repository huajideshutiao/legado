package io.legado.app.ui.book.audio

import android.animation.ArgbEvaluator
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.Interpolator
import android.view.animation.ScaleAnimation
import android.view.animation.Transformation
import android.widget.TextView
import androidx.core.content.ContextCompat.getColor
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.model.AudioPlay
import kotlin.apply

class LrcAdapter(private var lyrics: List<Pair<Int, String>>) :
  RecyclerView.Adapter<LrcAdapter.ViewHolder>() {

  private var currentIndex: Int? = null
  private var oldIndex: Int? = null

  class ViewHolder(view: TextView) : RecyclerView.ViewHolder(view){
      val primaryText = getColor(view.context, R.color.primaryText)
      val secondaryText = getColor(view.context, R.color.secondaryText)
    val textView = view
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val textView = TextView(parent.context).apply {
      layoutParams = RecyclerView.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
      )
      setPadding(0, 40, 0, 40)
      textSize = 24f
        gravity = Gravity.CENTER
    }
    return ViewHolder(textView)
  }
  override fun getItemCount() = lyrics.size
  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val item = lyrics[position]
    holder.textView.text = item.second.trim()
    animator(holder,position)
      holder.textView.setOnClickListener {
        AudioPlay.adjustProgress(item.first)
      }
  }
    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.textView.apply {
            setTextColor(holder.secondaryText)
//            clearAnimation()
//            animate().cancel()
        }
    }
//  private fun animator(view: TextView, position: Int) {
//    val primaryText = getColor(view.context, R.color.primaryText)
//    val secondaryText = getColor(view.context, R.color.secondaryText)
//    when (position) {
//        oldIndex -> animateView(view, 1f, primaryText, secondaryText)
//        currentIndex -> animateView(view, 1.05f, secondaryText,primaryText)
//    }
//}
//private fun animateView(view: TextView, scale: Float, startColor: Int,endColor: Int) {
//    view.animate()
//        .scaleX(scale).scaleY(scale)
//        .setDuration(300)
//        .start()
//    ValueAnimator.ofArgb(startColor,endColor).apply {
//        duration = 300
//        addUpdateListener { animator ->
//            view.setTextColor(animator.animatedValue as Int)
//        }
//        start()
//    }
//}
  private fun animator(holder: ViewHolder,position: Int) {
    // 创建缩放动画
    val scaleAnimation = ScaleAnimation(1f, 1.05f, 1f, 1.05f,
      Animation.RELATIVE_TO_SELF, 0.5f,Animation.RELATIVE_TO_SELF, 0.5f
    )
//     创建颜色渐变动画
    val colorAnimation = object : Animation() {
        override fun applyTransformation(interpolatedTime: Float, t: Transformation) {
            val currentColor = ArgbEvaluator().evaluate(interpolatedTime, holder.secondaryText, holder.primaryText) as Int
            holder.textView.setTextColor(currentColor)
        }
    }

      // 创建动画集并设置参数
     val animator = AnimationSet(true).apply {
        duration = 300
        addAnimation(scaleAnimation)
        addAnimation(colorAnimation)
        fillAfter = true
    }

    if (position == oldIndex) {
        holder.textView.animation = animator.apply {
          interpolator = Interpolator { input -> 1 - input }
      }
    }
    if (position ==currentIndex){
      holder.textView.animation = animator
    }

}
  // 更新当前行
  fun update(position: Int){
    if (position != currentIndex) {
      oldIndex = currentIndex
      currentIndex = position
      oldIndex?.let { notifyItemChanged(it) }
        notifyItemChanged(position)
    }
  }

  fun update(): Int {
    return currentIndex?:0
  }

  fun setData(newData: List<Pair<Int, String>>) {
    this.lyrics = newData
    notifyDataSetChanged()
  }
}