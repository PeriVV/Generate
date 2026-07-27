package com.casc.unittestagent.ui.component;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * 支持自动换行并正确计算高度的 FlowLayout。
 */
public class UnitMindWrapLayout extends FlowLayout {
    public UnitMindWrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return layoutSize(target);
    }

    private Dimension layoutSize(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int maxWidth = target.getWidth();
            if (maxWidth <= 0 && target.getParent() != null) {
                maxWidth = target.getParent().getWidth();
            }
            if (maxWidth <= 0) {
                maxWidth = Integer.MAX_VALUE;
            }
            maxWidth -= insets.left + insets.right + getHgap() * 2;

            int rowWidth = 0;
            int rowHeight = 0;
            int preferredWidth = 0;
            int preferredHeight = getVgap();
            int visibleCount = 0;

            for (Component component : target.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }
                Dimension size = component.getPreferredSize();
                int nextWidth = rowWidth == 0 ? size.width : rowWidth + getHgap() + size.width;
                if (nextWidth > maxWidth && rowWidth > 0) {
                    preferredWidth = Math.max(preferredWidth, rowWidth);
                    preferredHeight += rowHeight + getVgap();
                    rowWidth = size.width;
                    rowHeight = size.height;
                } else {
                    rowWidth = nextWidth;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                visibleCount++;
            }

            if (visibleCount > 0) {
                preferredWidth = Math.max(preferredWidth, rowWidth);
                preferredHeight += rowHeight + getVgap();
            }
            preferredWidth += insets.left + insets.right + getHgap() * 2;
            preferredHeight += insets.top + insets.bottom;
            return new Dimension(preferredWidth, preferredHeight);
        }
    }
}
