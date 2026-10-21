package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** A broad, frosted dock with a luminous upper lip and soft colour reflected through its glass. */
public class DockDecoration extends RecyclerView.ItemDecoration {
    private final int dockSize;
    private final float density;
    private boolean enabled = true;
    private boolean names;
    void setShowNames(boolean value) { names = value; }
    private final Paint body = new Paint(3), warmth = new Paint(3), reflection = new Paint(3);
    private final Paint edge = new Paint(3), innerEdge = new Paint(3);
    private final Paint[] layers = {body, warmth, reflection, edge, innerEdge};
    private final RectF rect = new RectF(), inner = new RectF();
    private final Matrix matrix = new Matrix();
    private float shaderWidth, shaderHeight;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f * density);
        innerEdge.setStyle(Paint.Style.STROKE);
        innerEdge.setStrokeWidth(.5f * density);
    }
    void setEnabled(boolean value) { enabled = value; }

    /** Extra space under the dock row that the rows below it do not have. */
    int rowInset() { return enabled ? Math.round(8 * density) : 0; }

    @Override public void getItemOffsets(@NonNull Rect out, @NonNull View view,
            @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        // With fewer apps than dock slots the App Library row follows the dock directly; it is not part of it.
        if (parent.getAdapter() instanceof AppAdapter && ((AppAdapter) parent.getAdapter()).isLibraryAt(pos)) return;
        if (enabled && pos >= 0 && pos < dockSize) {
            out.top = 0;
            out.bottom = rowInset();
        }
    }

    private void shaders(float width, float height) {
        if (shaderWidth == width && shaderHeight == height) return;
        shaderWidth = width; shaderHeight = height;
        body.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{0x65FFFFFF, 0x38F6F8F7, 0x50F8F4EC},
                new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
        warmth.setShader(new RadialGradient(width * .31f, height * .65f, width * .32f,
                0x25FFF1CE, 0x00FFF1CE, Shader.TileMode.CLAMP));
        reflection.setShader(new RadialGradient(width * .64f, -height * .2f, width * .35f,
                0x25DAFFFF, 0x00DAFFFF, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{0xC5ECFFFF, 0x5CD9E9E9, 0x38FFFFFF},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        innerEdge.setShader(new LinearGradient(0, 0, 0, height,
                0x38FFFFFF, 0x0CFFFFFF, Shader.TileMode.CLAMP));
    }

    @Override public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent,
            @NonNull RecyclerView.State state) {
        if (!enabled) return;
        boolean found = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            // Follow the laid-out slots, not app identities already shifted by notifyItemMoved.
            int pos = parent.getChildLayoutPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            // Reordering translates individual tiles. The dock belongs to the first row's layout
            // and must never travel with those tiles into another row.
            float t = child.getTop() + card.getTop();
            if (!found) { rect.set(l, t, l + card.getWidth(), t + card.getHeight()); found = true; }
            else rect.union(l, t, l + card.getWidth(), t + card.getHeight());
        }
        if (!found) return;
        float side = Math.max(0f, Math.min(18 * density,
                Math.min(rect.left, parent.getWidth() - rect.right) - density));
        rect.inset(-side, -16 * density);
        if (names) rect.bottom += 22 * density;
        float radius = 36 * density;
        shaders(rect.width(), rect.height());
        matrix.setTranslate(rect.left, rect.top);
        for (Paint paint : layers)
            paint.getShader().setLocalMatrix(matrix);
        canvas.drawRoundRect(rect, radius, radius, body);
        // The band's own shape bounds these two layers: the same pixels as clipping them to it, without a
        // path clip on every frame.
        canvas.drawRoundRect(rect, radius, radius, warmth);
        canvas.drawRoundRect(rect, radius, radius, reflection);
        canvas.drawRoundRect(rect, radius, radius, edge);
        inner.set(rect); inner.inset(density, density);
        canvas.drawRoundRect(inner, radius - density, radius - density, innerEdge);
    }
}
