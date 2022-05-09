package orllewin.lento.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.graphics.drawable.toBitmap
import orllewin.lento2.R

class RadioToggle @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatRadioButton(context, attrs) {

    private val deselected = Paint().apply {
        color = Color.parseColor("#3a3a3a")
    }

    private val selected = Paint().apply {
        color = Color.parseColor("#FFD4D4")
    }

    private var icon: Drawable? = null
    private var bitmapSelected: Bitmap? = null
    private var bitmapUnselected: Bitmap? = null

    init {
        buttonDrawable = null

        val typedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.RadioToggle, 0, 0)

        try {
            icon = typedArray.getDrawable(R.styleable.RadioToggle_drawable)

            val selectedForegroundTint = typedArray.getColor(R.styleable.RadioToggle_selectedForegroundTint, 0x000000)
            val unselectedForegroundTint = typedArray.getColor(R.styleable.RadioToggle_unselectedForegroundTint, 0xffffff)

            icon?.let{ i ->
                i.setTint(selectedForegroundTint)
                bitmapSelected = i.toBitmap(i.intrinsicWidth, i.intrinsicHeight, null)

                i.setTint(unselectedForegroundTint)
                bitmapUnselected = i.toBitmap(i.intrinsicWidth, i.intrinsicHeight, null)
            }

            val selectedBackgroundColour = typedArray.getColor(R.styleable.RadioToggle_selectedBackgroundColor, 0xff00cc)
            selected.color = selectedBackgroundColour

            val unselectedBackgroundColour = typedArray.getColor(R.styleable.RadioToggle_unselectedBackgroundColor, 0xffccff)
            deselected.color = unselectedBackgroundColour

        } finally {
            typedArray.recycle()
        }
    }

    override fun draw(canvas: Canvas?) {
        super.draw(canvas)

        canvas?.let{ c ->
            when {
                isChecked -> {
                    c.drawCircle(width/2f, height/2f, width.toFloat()/2, selected)
                    bitmapSelected?.let{ icon ->
                        c.drawBitmap(icon, (width - icon.width)/2f, (height - icon.height)/2f, selected)
                    }
                }
                else -> {
                    c.drawCircle(width/2f, height/2f, width.toFloat()/2, deselected)
                    bitmapUnselected?.let{ icon ->
                        c.drawBitmap(icon, (width - icon.width)/2f, (height - icon.height)/2f, selected)
                    }
                }
            }
        }
    }
}