package org.thoughtcrime.securesms.badges.gifts

import android.animation.FloatEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.view.children
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.util.DimensionUnit
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.Projection
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Controls the gift box top and related animations for Gift bubbles.
 */
class OpenableGiftItemDecoration(context: Context) : RecyclerView.ItemDecoration(), DefaultLifecycleObserver {

  private val animatorDurationScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
  private val messageIdsShakenThisSession = mutableSetOf<Long>()
  private val messageIdsOpenedThisSession = mutableSetOf<Long>()
  private val animationState = mutableMapOf<Long, GiftAnimationState>()

  private val rect = RectF()
  private val lineWidth = DimensionUnit.DP.toPixels(24f).toInt()

  private val boxPaint = Paint().apply {
    isAntiAlias = true
    color = ContextCompat.getColor(context, R.color.core_ultramarine)
  }

  private val ribbonPaint = Paint().apply {
    isAntiAlias = true
    color = Color.WHITE
  }

  override fun onDestroy(owner: LifecycleOwner) {
    super.onDestroy(owner)
    animationState.clear()
  }

  override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    var needsInvalidation = false
    val openableChildren = parent.children.filterIsInstance(OpenableGift::class.java)

    val deadKeys = animationState.keys.filterNot { giftId -> openableChildren.any { it.getGiftId() == giftId } }
    deadKeys.forEach {
      animationState.remove(it)
    }

    val notAnimated = openableChildren.filterNot { animationState.containsKey(it.getGiftId()) }

    notAnimated.filterNot { messageIdsOpenedThisSession.contains(it.getGiftId()) }.forEach { child ->
      val projection = child.getOpenableGiftProjection()
      if (projection != null) {
        if (messageIdsShakenThisSession.contains(child.getGiftId())) {
          child.setOpenGiftCallback {
            child.clearOpenGiftCallback()
            val proj = it.getOpenableGiftProjection()
            if (proj != null) {
              messageIdsOpenedThisSession.add(it.getGiftId())
              startOpenAnimation(it)
              parent.invalidate()
            }
          }

          drawGiftBox(c, projection)
          drawGiftBow(c, projection)
        } else {
          messageIdsShakenThisSession.add(child.getGiftId())
          startShakeAnimation(child)

          drawGiftBox(c, projection)
          drawGiftBow(c, projection)

          needsInvalidation = true
        }

        projection.release()
      }
    }

    openableChildren.filter { animationState.containsKey(it.getGiftId()) }.forEach { child ->
      val runningAnimation = animationState[child.getGiftId()]!!
      c.withSave {
        val isThisAnimationRunning = runningAnimation.update(
          animatorDurationScale = animatorDurationScale,
          canvas = c,
          drawBox = this@OpenableGiftItemDecoration::drawGiftBox,
          drawBow = this@OpenableGiftItemDecoration::drawGiftBow
        )

        if (!isThisAnimationRunning) {
          animationState.remove(child.getGiftId())
        }

        needsInvalidation = true
      }
    }

    if (needsInvalidation) {
      parent.invalidate()
    }
  }

  private fun drawGiftBox(canvas: Canvas, projection: Projection) {
    canvas.drawPath(projection.path, boxPaint)

    rect.set(
      projection.x + (projection.width / 2) - lineWidth / 2,
      projection.y,
      projection.x + (projection.width / 2) + lineWidth / 2,
      projection.y + projection.height
    )

    canvas.drawRect(rect, ribbonPaint)

    rect.set(
      projection.x,
      projection.y + (projection.height / 2) - lineWidth / 2,
      projection.x + projection.width,
      projection.y + (projection.height / 2) + lineWidth / 2
    )

    canvas.drawRect(rect, ribbonPaint)
  }

  private fun drawGiftBow(canvas: Canvas, projection: Projection) {
    rect.set(
      projection.x + (projection.width / 2) - lineWidth,
      projection.y + (projection.height / 2) - lineWidth,
      projection.x + (projection.width / 2) + lineWidth,
      projection.y + (projection.height / 2) + lineWidth
    )

    canvas.drawRect(rect, ribbonPaint)
  }

  private fun startShakeAnimation(child: OpenableGift) {
    animationState[child.getGiftId()] = GiftAnimationState.ShakeAnimationState(child, System.currentTimeMillis())
  }

  private fun startOpenAnimation(child: OpenableGift) {
    animationState[child.getGiftId()] = GiftAnimationState.OpenAnimationState(child, System.currentTimeMillis())
  }

  sealed class GiftAnimationState(val openableGift: OpenableGift, val startTime: Long) {

    /**
     * Shakes the gift box to the left and right, slightly revealing the contents underneath.
     * Uses a lag value to keep the bow one "frame" behind the box, to give it the effect of
     * following behind.
     */
    class ShakeAnimationState(openableGift: OpenableGift, startTime: Long) : GiftAnimationState(openableGift, startTime) {
      override fun update(canvas: Canvas, projection: Projection, progress: Float, lastFrameProgress: Float, drawBox: (Canvas, Projection) -> Unit, drawBow: (Canvas, Projection) -> Unit) {
        canvas.withTranslation(x = getTranslation(progress).toFloat()) {
          drawBox(canvas, projection)
        }

        canvas.withTranslation(x = getTranslation(lastFrameProgress).toFloat()) {
          drawBow(canvas, projection)
        }
      }

      private fun getTranslation(progress: Float): Double {
        val interpolated = INTERPOLATOR.getInterpolation(progress)
        val evaluated = EVALUATOR.evaluate(interpolated, 0f, 360f)

        return 0.25f * sin(4 * evaluated * PI / 180f) * 180f / PI
      }
    }

    class OpenAnimationState(openableGift: OpenableGift, startTime: Long) : GiftAnimationState(openableGift, startTime) {
      override fun update(canvas: Canvas, projection: Projection, progress: Float, lastFrameProgress: Float, drawBox: (Canvas, Projection) -> Unit, drawBow: (Canvas, Projection) -> Unit) {
        val interpolatedProgress = INTERPOLATOR.getInterpolation(progress)
        val evaluatedValue = EVALUATOR.evaluate(interpolatedProgress, 0f, DimensionUnit.DP.toPixels(300f))

        canvas.translate(evaluatedValue, -evaluatedValue)

        drawBox(canvas, projection)
        drawBow(canvas, projection)
      }
    }

    fun update(animatorDurationScale: Float, canvas: Canvas, drawBox: (Canvas, Projection) -> Unit, drawBow: (Canvas, Projection) -> Unit): Boolean {
      val projection = openableGift.getOpenableGiftProjection() ?: return false

      if (animatorDurationScale <= 0f) {
        update(canvas, projection, 0f, 0f, drawBox, drawBow)
        projection.release()
        return false
      }

      val currentFrameTime = System.currentTimeMillis()
      val lastFrameProgress = max(0f, (currentFrameTime - startTime - ONE_FRAME_RELATIVE_TO_30_FPS_MILLIS) / (DURATION_MILLIS.toFloat() * animatorDurationScale))
      val progress = (currentFrameTime - startTime) / (DURATION_MILLIS.toFloat() * animatorDurationScale)

      if (progress > 1f) {
        update(canvas, projection, 1f, 1f, drawBox, drawBow)
        projection.release()
        return false
      }

      update(canvas, projection, progress, lastFrameProgress, drawBox, drawBow)
      projection.release()
      return true
    }

    protected abstract fun update(
      canvas: Canvas,
      projection: Projection,
      progress: Float,
      lastFrameProgress: Float,
      drawBox: (Canvas, Projection) -> Unit,
      drawBow: (Canvas, Projection) -> Unit
    )
  }

  companion object {
    private val INTERPOLATOR = AccelerateDecelerateInterpolator()
    private val EVALUATOR = FloatEvaluator()

    private const val DURATION_MILLIS = 1000
    private const val ONE_FRAME_RELATIVE_TO_30_FPS_MILLIS = 33
  }
}
