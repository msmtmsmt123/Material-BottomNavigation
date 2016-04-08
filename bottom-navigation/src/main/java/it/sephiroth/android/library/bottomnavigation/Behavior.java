package it.sephiroth.android.library.bottomnavigation;

import android.content.Context;
import android.content.res.TypedArray;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar.SnackbarLayout;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewPropertyAnimatorCompat;
import android.support.v4.view.ViewPropertyAnimatorUpdateListener;
import android.support.v4.view.animation.LinearOutSlowInInterpolator;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.animation.Interpolator;

import java.util.HashMap;

import it.sephiroth.android.library.bottonnavigation.R;
import proguard.annotation.Keep;
import proguard.annotation.KeepClassMembers;

import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;
import static android.util.Log.INFO;
import static android.util.Log.VERBOSE;
import static it.sephiroth.android.library.bottomnavigation.BottomNavigation.PENDING_ACTION_ANIMATE_ENABLED;
import static it.sephiroth.android.library.bottomnavigation.BottomNavigation.PENDING_ACTION_NONE;
import static it.sephiroth.android.library.bottomnavigation.MiscUtils.log;

/**
 * Created by alessandro on 4/2/16.
 */
@Keep
@KeepClassMembers
public class Behavior extends VerticalScrollingBehavior<BottomNavigation> {
    private static final String TAG = Behavior.class.getSimpleName();

    private boolean scrollable;
    private boolean scrollEnabled;
    private boolean enabled;

    /**
     * default hide/show interpolator
     */
    private static final Interpolator INTERPOLATOR = new LinearOutSlowInInterpolator();

    /**
     * show/hide animation duration
     */
    private final int animationDuration;

    /**
     * bottom inset when TRANSLUCENT_NAVIGATION is turned on
     */
    private int bottomInset;

    /**
     * bottom navigation real height
     */
    private int height;

    /**
     * maximum scroll offset
     */
    private int maxOffset;

    /**
     * true if the current configuration has the TRANSLUCENT_NAVIGATION turned on
     */
    private boolean translucentNavigation;

    /**
     * Minimum touch distance
     */
    private final int scaledTouchSlop;

    /**
     * hide/show animator
     */
    private ViewPropertyAnimatorCompat animator;

    /**
     * current visibility status
     */
    private boolean hidden = false;

    /**
     * current Y offset
     */
    private int offset;

    //    private final LollipopBottomNavWithSnackBarImpl mWithSnackBarImpl = new LollipopBottomNavWithSnackBarImpl();

    private final HashMap<View, DependentView> dependentViewHashMap = new HashMap<>();
    private SnackBarDependentView snackBarDependentView;
    private FabDependentView fabDependentView;

    public Behavior() {
        this(null, null);
    }

    public Behavior(final Context context, AttributeSet attrs) {
        super(context, attrs);
        log(TAG, INFO, "ctor(attrs:%s)", attrs);

        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.BottomNavigationBehavior);
        this.scrollable = array.getBoolean(R.styleable.BottomNavigationBehavior_bbn_scrollEnabled, true);
        this.scrollEnabled = true;
        this.animationDuration = array.getInt(
            R.styleable.BottomNavigationBehavior_bbn_animationDuration,
            context.getResources().getInteger(R.integer.bbn_hide_animation_duration)
        );
        this.scaledTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
        this.offset = 0;
        array.recycle();

        log(TAG, DEBUG, "scrollable: %b, duration: %d, touchSlop: %d", scrollable, animationDuration, scaledTouchSlop);
    }

    public boolean isScrollable() {
        return scrollable;
    }

    public boolean isExpanded() {
        return !hidden;
    }

    public void setLayoutValues(final int bottomNavHeight, final int bottomInset) {
        log(TAG, INFO, "setLayoutValues(%d, %d)", bottomNavHeight, bottomInset);
        this.height = bottomNavHeight;
        this.bottomInset = bottomInset;
        this.translucentNavigation = bottomInset > 0;
        this.maxOffset = height + (translucentNavigation ? bottomInset : 0);
        this.enabled = true;
        log(
            TAG, DEBUG, "height: %d, translucent: %b, maxOffset: %d, bottomInset: %d", height, translucentNavigation, maxOffset,
            bottomInset
        );
    }

    @Override
    public boolean layoutDependsOn(CoordinatorLayout parent, BottomNavigation child, View dependency) {
        if (!enabled) {
            return false;
        }

        if (FloatingActionButton.class.isInstance(dependency)
            || SnackbarLayout.class.isInstance(dependency)) {
            return true;
        }

        if (!scrollable) {
            return RecyclerView.class.isInstance(dependency);
        }

        return false;
    }

    @Override
    public boolean onLayoutChild(CoordinatorLayout parent, BottomNavigation abl, int layoutDirection) {
        boolean handled = super.onLayoutChild(parent, abl, layoutDirection);

        final int pendingAction = abl.getPendingAction();
        if (pendingAction != PENDING_ACTION_NONE) {
            final boolean animate = (pendingAction & PENDING_ACTION_ANIMATE_ENABLED) != 0;
            if ((pendingAction & BottomNavigation.PENDING_ACTION_COLLAPSED) != 0) {
                setExpanded(parent, abl, false, animate);
            } else {
                if ((pendingAction & BottomNavigation.PENDING_ACTION_EXPANDED) != 0) {
                    setExpanded(parent, abl, true, animate);
                }
            }
            // Finally reset the pending state
            abl.resetPendingAction();
        }

        return handled;
    }

    @Override
    public void onDependentViewRemoved(CoordinatorLayout parent, BottomNavigation child, View dependency) {
        log(TAG, ERROR, "onDependentViewRemoved(%s)", dependency.getClass().getSimpleName());

        if (FloatingActionButton.class.isInstance(dependency)) {
            fabDependentView = null;
        } else if (SnackbarLayout.class.isInstance(dependency)) {
            snackBarDependentView = null;

            if (null != fabDependentView) {
                fabDependentView.onDependentViewChanged(parent, child);
            }
        }

        final DependentView dependent = dependentViewHashMap.remove(dependency);
        log(TAG, ERROR, "removed: %s", dependent);
        if (null != dependent) {
            dependent.onDestroy();
        }
    }

    @Override
    public boolean onDependentViewChanged(CoordinatorLayout parent, BottomNavigation child, View dependency) {
        boolean isFab = FloatingActionButton.class.isInstance(dependency);
        boolean isSnackBack = SnackbarLayout.class.isInstance(dependency);

        DependentView dependent = null;

        if (!dependentViewHashMap.containsKey(dependency)) {
            if (!isFab && !isSnackBack) {
                dependent = new GenericDependentView(dependency);
            } else if (isFab) {
                dependent = new FabDependentView((FloatingActionButton) dependency);
                fabDependentView = (FabDependentView) dependent;
            } else {
                dependent = new SnackBarDependentView((SnackbarLayout) dependency);
                snackBarDependentView = (SnackBarDependentView) dependent;
            }
            dependentViewHashMap.put(dependency, dependent);
        } else {
            dependent = dependentViewHashMap.get(dependency);
        }

        if (null != dependent) {
            return dependent.onDependentViewChanged(parent, child);
        }

        return true;
    }

    @Override
    public boolean onStartNestedScroll(
        final CoordinatorLayout coordinatorLayout,
        final BottomNavigation child,
        final View directTargetChild, final View target,
        final int nestedScrollAxes) {

        offset = 0;
        if (!scrollable || !scrollEnabled) {
            return false;
        }
        return super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, nestedScrollAxes);
    }

    @Override
    public void onStopNestedScroll(final CoordinatorLayout coordinatorLayout, final BottomNavigation child, final View target) {
        super.onStopNestedScroll(coordinatorLayout, child, target);
        offset = 0;
    }

    @Override
    public void onDirectionNestedPreScroll(
        CoordinatorLayout coordinatorLayout,
        BottomNavigation child,
        View target, int dx, int dy, int[] consumed,
        @ScrollDirection int scrollDirection) {

        offset += dy;

        if (offset > scaledTouchSlop) {
            handleDirection(coordinatorLayout, child, ScrollDirection.SCROLL_DIRECTION_UP);
            offset = 0;
        } else if (offset < -scaledTouchSlop) {
            handleDirection(coordinatorLayout, child, ScrollDirection.SCROLL_DIRECTION_DOWN);
            offset = 0;
        }
    }

    @Override
    protected boolean onNestedDirectionFling(
        CoordinatorLayout coordinatorLayout, BottomNavigation child, View target, float velocityX, float velocityY,
        @ScrollDirection int scrollDirection) {
        return true;
    }

    @Override
    public void onNestedVerticalOverScroll(
        CoordinatorLayout coordinatorLayout, BottomNavigation child, @ScrollDirection int direction, int currentOverScroll,
        int totalOverScroll) {
    }

    private void handleDirection(final CoordinatorLayout coordinatorLayout, BottomNavigation child, int scrollDirection) {
        if (!enabled || !scrollable || !scrollEnabled) {
            return;
        }
        if (scrollDirection == ScrollDirection.SCROLL_DIRECTION_DOWN && hidden) {
            setExpanded(coordinatorLayout, child, true, true);
        } else if (scrollDirection == ScrollDirection.SCROLL_DIRECTION_UP && !hidden) {
            setExpanded(coordinatorLayout, child, false, true);
        }
    }

    private void setExpanded(
        final CoordinatorLayout coordinatorLayout, final BottomNavigation child, boolean expanded, boolean animate) {
        log(TAG, INFO, "setExpanded(%b)", expanded);
        if (animate) {
            animateOffset(coordinatorLayout, child, expanded ? 0 : maxOffset);
        }
    }

    private void animateOffset(final CoordinatorLayout coordinatorLayout, final BottomNavigation child, final int offset) {
        log(TAG, INFO, "animateOffset(%d)", offset);
        hidden = offset != 0;
        ensureOrCancelAnimator(coordinatorLayout, child);
        animator.translationY(offset).start();
    }

    private void ensureOrCancelAnimator(final CoordinatorLayout coordinatorLayout, final BottomNavigation child) {
        if (animator == null) {
            animator = ViewCompat.animate(child);
            animator.setDuration(animationDuration);
            animator.setInterpolator(INTERPOLATOR);
            animator.setUpdateListener(new ViewPropertyAnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(final View view) {
                    if (null != fabDependentView) {
                        fabDependentView.onDependentViewChanged(coordinatorLayout, child);
                    }
                }
            });
        } else {
            animator.cancel();
        }
    }

    private abstract class DependentView<V extends View> {
        final V child;
        final MarginLayoutParams layoutParams;
        final int bottomMargin;

        DependentView(V child) {
            this.child = child;
            this.layoutParams = (MarginLayoutParams) child.getLayoutParams();
            this.bottomMargin = layoutParams.bottomMargin;
        }

        void onDestroy() { }

        abstract boolean onDependentViewChanged(CoordinatorLayout parent, BottomNavigation navigation);
    }

    private class GenericDependentView extends DependentView<View> {
        final String TAG = GenericDependentView.class.getSimpleName();

        GenericDependentView(final View child) {
            super(child);
            log(TAG, INFO, "new GenericDependentView(%s)", child.getClass().getSimpleName());
        }

        @Override
        void onDestroy() { }

        @Override
        boolean onDependentViewChanged(final CoordinatorLayout parent, final BottomNavigation navigation) {
            log(TAG, VERBOSE, "onDependentViewChanged");
            layoutParams.bottomMargin = bottomMargin + height;
            return true;
        }
    }

    private class FabDependentView extends DependentView<FloatingActionButton> {
        final String TAG = FabDependentView.class.getSimpleName();

        FabDependentView(final FloatingActionButton child) {
            super(child);
            log(TAG, INFO, "new FabDependentView");
        }

        @Override
        boolean onDependentViewChanged(final CoordinatorLayout parent, final BottomNavigation navigation) {
            final float t = Math.max(0, navigation.getTranslationY() - height);
            // log(TAG, VERBOSE, "onDependentViewChanged(%g, %d, %d)", navigation.getTranslationY(), height, bottomInset);

            if (bottomInset > 0) {
                layoutParams.bottomMargin = (int) (bottomMargin + height - t);
            } else {
                layoutParams.bottomMargin = (int) (bottomMargin + height - navigation.getTranslationY());
            }
            child.requestLayout();
            return true;
        }

        @Override
        void onDestroy() { }
    }

    private class SnackBarDependentView extends DependentView<SnackbarLayout> {
        final String TAG = SnackBarDependentView.class.getSimpleName();
        private int snackbarHeight = -1;

        SnackBarDependentView(final SnackbarLayout child) {
            super(child);
            log(TAG, INFO, "new SnackBarDependentView");
        }

        @Override
        boolean onDependentViewChanged(final CoordinatorLayout parent, final BottomNavigation navigation) {
            log(TAG, VERBOSE, "onDependentViewChanged");

            scrollEnabled = false;
            final boolean expanded = navigation.getTranslationY() == 0;
            if (snackbarHeight == -1) {
                snackbarHeight = child.getHeight();
            }

            log(TAG, VERBOSE, "snackbarheight: %d, height: %d, translationY: %g, expanded: %b",
                snackbarHeight,
                height,
                child.getTranslationY(),
                expanded
            );

            layoutParams.bottomMargin = expanded ? height : 0;
            child.requestLayout();

            return true;
        }

        @Override
        void onDestroy() {
            log(TAG, INFO, "onDestroy");
            scrollEnabled = true;
        }
    }
}