package se.emilsjolander.flipviewPager;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.Scroller;

import androidx.core.view.MotionEventCompat;
import androidx.core.view.VelocityTrackerCompat;
import androidx.viewpager.widget.PagerAdapter;

import com.flipview.library.R;

public class FlipView extends FrameLayout {

    private int storedVisibility;

    public interface OnFlipListener {
        public void onFlippedToPage(FlipView v, int position);

    }

    public interface OnFlipScrollListener {
        enum ScrollState {
            START,
            FLIPPING,
            END
        }
        public void onFlip(FlipView v, ScrollState state);
    }

    public interface OnOverFlipListener {
        public void onOverFlip(FlipView v, OverFlipMode mode,
                               boolean overFlippingPrevious, float overFlipDistance,
                               float flipDistancePerPage);
    }

    /**
     * @author emilsjolander
     * <p>
     * Class to hold a view and its corresponding info
     */
    static class Page {
        Object item;
        View view;
        int position;
        boolean valid;

        public void setInValid() {
            this.valid = false;
        }

        public void setValid() {
            this.valid = true;
        }
    }

    // "null" flip distance
    private static final int INVALID_FLIP_DISTANCE = -1;

    private static final int PEAK_ANIM_DURATION = 600;// in ms
    private static final int MAX_SINGLE_PAGE_FLIP_ANIM_DURATION = 300;// in ms

    // for normalizing width/height
    private static final int FLIP_DISTANCE_PER_PAGE = 180;
    private static final int MAX_SHADOW_ALPHA = 180;// out of 255
    private static final int MAX_SHADE_ALPHA = 130;// out of 255
    private static final int MAX_SHINE_ALPHA = 100;// out of 255

    // value for no pointer
    private static final int INVALID_POINTER = -1;

    // constant used by the attributes
    private static final int VERTICAL_FLIP = 0;

    // constant used by the attributes
    @SuppressWarnings("unused")
    private static final int HORIZONTAL_FLIP = 1;

    private DataSetObserver dataSetObserver = new DataSetObserver() {

        @Override
        public void onChanged() {
            dataSetChanged();
        }

        @Override
        public void onInvalidated() {
            dataSetInvalidated();
        }

    };

    private Scroller mScroller;
    private final Interpolator flipInterpolator = new DecelerateInterpolator();
    private ValueAnimator mPeakAnim;
    private TimeInterpolator mPeakInterpolator = new AccelerateDecelerateInterpolator();

    private boolean mIsFlippingVertically = true;
    private boolean flipping;

    private boolean isFlipping() {
        return flipping;
    }

    private void setFlipping(boolean value) {
        if (flipping != value && mOnFlipListener != null) {
            if (flipping) {
                flipScrollListener.onFlip(this, OnFlipScrollListener.ScrollState.START);
            } else {
                flipScrollListener.onFlip(this,OnFlipScrollListener.ScrollState.END);
            }
        }
        flipping = value;
    }

    private boolean mIsUnableToFlip;
    private boolean mIsFlippingEnabled = true;
    private boolean mLastTouchAllowed = true;
    private int mTouchSlop;
    private boolean mIsOverFlipping;


    // keep track of pointer
    private float mLastX = -1;
    private float mLastY = -1;
    private int mActivePointerId = INVALID_POINTER;

    // velocity stuff
    private VelocityTracker mVelocityTracker;
    private int mMinimumVelocity;
    private int mMaximumVelocity;

    // views get recycled after they have been pushed out of the active queue

    private PagerAdapter mAdapter;
    private int mPageCount = 0;
    private Page mPreviousPage = new Page();
    private Page mCurrentPage = new Page();
    private Page mNextPage = new Page();
    private View mEmptyView;

    private OnFlipListener mOnFlipListener;
    private OnFlipScrollListener flipScrollListener;
    private OnOverFlipListener mOnOverFlipListener;

    private float mFlipDistance = INVALID_FLIP_DISTANCE;
    private int mCurrentPageIndex = 0;
    private int mLastDispatchedPageEventIndex = -1;
    private boolean flipNotificationPending = false;

    private OverFlipMode mOverFlipMode;
    private OverFlipper mOverFlipper;

    // clipping rects
    private Rect mTopRect = new Rect();
    private Rect mBottomRect = new Rect();
    private Rect mRightRect = new Rect();
    private Rect mLeftRect = new Rect();

    // used for transforming the canvas
    private Camera mCamera = new Camera();
    private Matrix mMatrix = new Matrix();

    // paints drawn above views when flipping
    private Paint mShadowPaint = new Paint();
    private Paint mShadePaint = new Paint();
    private Paint mShinePaint = new Paint();

    public FlipView(Context context) {
        this(context, null);
    }

    public FlipView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlipView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.FlipView);

        // 0 is vertical, 1 is horizontal
        mIsFlippingVertically = a.getInt(R.styleable.FlipView_orientation,
                VERTICAL_FLIP) == VERTICAL_FLIP;

        setOverFlipMode(OverFlipMode.values()[a.getInt(
                R.styleable.FlipView_overFlipMode, 0)]);

        a.recycle();

        init();
    }

    private void init() {
        final Context context = getContext();
        final ViewConfiguration configuration = ViewConfiguration.get(context);

        mScroller = new Scroller(context, flipInterpolator);
        mTouchSlop = configuration.getScaledPagingTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();

        mShadowPaint.setColor(Color.BLACK);
        mShadowPaint.setStyle(Style.FILL);
        mShadePaint.setColor(Color.BLACK);
        mShadePaint.setStyle(Style.FILL);
        mShinePaint.setColor(Color.WHITE);
        mShinePaint.setStyle(Style.FILL);
    }

    private int getAdapterPosition(Page page) {
        return page.item == null ? PagerAdapter.POSITION_NONE : mAdapter.getItemPosition(page.item);
    }

    private void dataSetChanged() {
        int newPosition = getAdapterPosition(mCurrentPage);

        mPageCount = mAdapter.getCount();

        if (newPosition == PagerAdapter.POSITION_NONE) {
            mFlipDistance = INVALID_FLIP_DISTANCE;
            mCurrentPageIndex = PagerAdapter.POSITION_NONE;
            setFlipDistance(0);
        } else {
            preservePages(newPosition);
        }

        updateEmptyStatus();
    }

    private void preservePages(int newPosition) {
        preserveCurrentPage(newPosition);
        updatePreviousPage(mCurrentPage.position);
        updateNextPage(mCurrentPage.position);
    }

    private void updatePreviousPage(int newPosition) {
        if (newPosition > 0) {
            int previous = getAdapterPosition(mPreviousPage);
            previous = previous == PagerAdapter.POSITION_UNCHANGED ? mPreviousPage.position : previous;
            if (previous == PagerAdapter.POSITION_NONE || previous != newPosition - 1) {
                previous = newPosition - 1;
                destroyPage(mPreviousPage);
                addView(mPreviousPage, previous);
                removeView(mPreviousPage.view);
                addView(mPreviousPage.view, 0);
            }
            mPreviousPage.position = previous;
        } else {
            destroyPage(mPreviousPage);
        }

    }

    private void preserveCurrentPage(int newPosition) {
        newPosition = newPosition == PagerAdapter.POSITION_UNCHANGED ? mCurrentPageIndex : newPosition;
        mFlipDistance = newPosition * FLIP_DISTANCE_PER_PAGE;
        final int currentPageIndex = (int) Math.round(mFlipDistance
                / FLIP_DISTANCE_PER_PAGE);
        newPosition = currentPageIndex;
        mLastDispatchedPageEventIndex = mCurrentPageIndex = mCurrentPage.position = newPosition;
    }

    private void updateNextPage(int newPosition) {
        if (newPosition < mPageCount - 1) {
            int next = getAdapterPosition(mNextPage);
            next = next == PagerAdapter.POSITION_UNCHANGED ? mNextPage.position : next;
            if (next == PagerAdapter.POSITION_NONE || next != newPosition + 1) {
                next = newPosition + 1;
                destroyPage(mNextPage);
                addView(mNextPage, next);
                removeView(mNextPage.view);
                addView(mNextPage.view, 0);
            }
            mNextPage.position = next;
        } else {
            destroyPage(mNextPage);
        }
    }


    private void dataSetInvalidated() {
        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(dataSetObserver);
            mAdapter = null;
        }
        removeAllViews();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(0, widthMeasureSpec);
        int height = getDefaultSize(0, heightMeasureSpec);

        measureChildren(widthMeasureSpec, heightMeasureSpec);

        setMeasuredDimension(width, height);
    }

    @Override
    protected void measureChildren(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(0, widthMeasureSpec);
        int height = getDefaultSize(0, heightMeasureSpec);

        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width,
                MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height,
                MeasureSpec.EXACTLY);
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            measureChild(child, childWidthMeasureSpec, childHeightMeasureSpec);
        }
    }

    @Override
    protected void measureChild(View child, int parentWidthMeasureSpec,
                                int parentHeightMeasureSpec) {
        child.measure(parentWidthMeasureSpec, parentHeightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutChildren();

        mTopRect.top = 0;
        mTopRect.left = 0;
        mTopRect.right = getWidth();
        mTopRect.bottom = getHeight() / 2;

        mBottomRect.top = getHeight() / 2;
        mBottomRect.left = 0;
        mBottomRect.right = getWidth();
        mBottomRect.bottom = getHeight();

        mLeftRect.top = 0;
        mLeftRect.left = 0;
        mLeftRect.right = getWidth() / 2;
        mLeftRect.bottom = getHeight();

        mRightRect.top = 0;
        mRightRect.left = getWidth() / 2;
        mRightRect.right = getWidth();
        mRightRect.bottom = getHeight();
    }

    private void layoutChildren() {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            layoutChild(child);
        }
    }

    private void layoutChild(View child) {
        child.layout(0, 0, getWidth(), getHeight());
    }

    private void setFlipDistance(float flipDistance) {
        if(isFlipping() && mOnFlipListener != null) {
            flipScrollListener.onFlip(this,OnFlipScrollListener.ScrollState.FLIPPING);
        }
        flipDistance = Math.max(0, flipDistance);
        if (mPageCount < 1) {
            mFlipDistance = 0;
            mCurrentPageIndex = PagerAdapter.POSITION_NONE;
            removeActiveViews();
            return;
        }

        if (flipDistance == mFlipDistance) {
            return;
        }

        mFlipDistance = flipDistance;

        final int currentPageIndex = (int) Math.round(mFlipDistance
                / FLIP_DISTANCE_PER_PAGE);

        if (mCurrentPageIndex != currentPageIndex) {
            boolean jump = mCurrentPageIndex < 0 || Math.abs(mCurrentPageIndex - currentPageIndex) != 1;
            boolean isNext = (currentPageIndex - mCurrentPageIndex) == 1;
            mCurrentPageIndex = currentPageIndex;


            // TODO be smarter about this. Dont remove a view that will be added
            // again on the next line.
            if (jump) {
                removeActiveViews();

                // add the new active views
                if (mCurrentPageIndex > 0) {
                    addView(mPreviousPage, mCurrentPageIndex - 1);
                }
                if (mCurrentPageIndex >= 0 && mCurrentPageIndex < mPageCount) {
                    addView(mCurrentPage, mCurrentPageIndex);
                }
                if (mCurrentPageIndex < mPageCount - 1) {
                    addView(mNextPage, mCurrentPageIndex + 1);
                }
            } else {
                if (isNext) {
                    destroyPage(mPreviousPage);
                    copy(mCurrentPage, mPreviousPage);
                    copy(mNextPage, mCurrentPage);
                    if (mCurrentPageIndex < mPageCount - 1)
                        addView(mNextPage, mCurrentPageIndex + 1);
                    else {
                        mNextPage.setInValid();
                        mNextPage.position = -1;
                        mNextPage.view = null;
                        mNextPage.item = null;
                    }
                } else {
                    destroyPage(mNextPage);
                    copy(mCurrentPage, mNextPage);
                    copy(mPreviousPage, mCurrentPage);
                    if (mCurrentPageIndex > 0)
                        addView(mPreviousPage, mCurrentPageIndex - 1);
                    else {
                        mPreviousPage.setInValid();
                        mPreviousPage.position = -1;
                        mPreviousPage.view = null;
                        mPreviousPage.item = null;
                    }
                }
                postFlippedToPage(mCurrentPage.position);
            }

        }

        invalidate();
    }

    private void copy(Page from, Page to) {
        if (from.valid) {
            to.setValid();
        } else to.setInValid();
//        to.valid = from.valid;
        to.view = from.view;
        to.position = from.position;
        to.item = from.item;
    }

    private void addView(Page page, int index) {
        page.position = index;
        page.item = mAdapter.instantiateItem(this, page.position);
        page.view = getChildAt(getChildCount() - 1);
        page.setValid();
    }

    private void destroyPage(Page page) {
        if (page.valid) {
            if (mAdapter != null)
                mAdapter.destroyItem(this, page.position, page.item);
//            mPreviousPage.view = null;
            removeView(page.view);
            page.view = null;
            page.setInValid();
        }
    }

    private void removeActiveViews() {
        // remove and recycle the currently active views
        destroyPage(mPreviousPage);
        destroyPage(mCurrentPage);
        destroyPage(mNextPage);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {

        if (!mIsFlippingEnabled) {
            return false;
        }

        if (mPageCount < 1) {
            return false;
        }

        final int action = ev.getAction() & MotionEvent.ACTION_MASK;

        if (action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_UP) {
            setFlipping(false);
            mIsUnableToFlip = false;
            mActivePointerId = INVALID_POINTER;
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
            return false;
        }

        if (action != MotionEvent.ACTION_DOWN) {
            if (isFlipping()) {
                return true;
            } else if (mIsUnableToFlip) {
                return false;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    break;
                }

                final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                        activePointerId);
                if (pointerIndex == -1) {
                    mActivePointerId = INVALID_POINTER;
                    break;
                }

                final float x = MotionEventCompat.getX(ev, pointerIndex);
                final float dx = x - mLastX;
                final float xDiff = Math.abs(dx);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float dy = y - mLastY;
                final float yDiff = Math.abs(dy);

                if ((mIsFlippingVertically && yDiff > mTouchSlop && yDiff > xDiff)
                        || (!mIsFlippingVertically && xDiff > mTouchSlop && xDiff > yDiff)) {
                    setFlipping(true);
                    mLastX = x;
                    mLastY = y;
                } else if ((mIsFlippingVertically && xDiff > mTouchSlop)
                        || (!mIsFlippingVertically && yDiff > mTouchSlop)) {
                    mIsUnableToFlip = true;
                }
                break;

            case MotionEvent.ACTION_DOWN:
                mActivePointerId = ev.getAction()
                        & MotionEvent.ACTION_POINTER_INDEX_MASK;
                mLastX = MotionEventCompat.getX(ev, mActivePointerId);
                mLastY = MotionEventCompat.getY(ev, mActivePointerId);

                setFlipping(!mScroller.isFinished() | mPeakAnim != null);
                mIsUnableToFlip = false;
                mLastTouchAllowed = true;

                break;
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        if (!isFlipping()) {
            trackVelocity(ev);
        }

        return isFlipping();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {

        if (!mIsFlippingEnabled) {
            return false;
        }

        if (mPageCount < 1) {
            return false;
        }

        if (!isFlipping() && !mLastTouchAllowed) {
            return false;
        }

        final int action = ev.getAction();

        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_OUTSIDE) {
            mLastTouchAllowed = false;
        } else {
            mLastTouchAllowed = true;
        }

        trackVelocity(ev);

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:

                // start flipping immediately if interrupting some sort of animation
                if (endScroll() || endPeak()) {
                    setFlipping(true);
                }

                // Remember where the motion event started
                mLastX = ev.getX();
                mLastY = ev.getY();
                mActivePointerId = MotionEventCompat.getPointerId(ev, 0);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!isFlipping()) {
                    final int pointerIndex = MotionEventCompat.findPointerIndex(ev,
                            mActivePointerId);
                    if (pointerIndex == -1) {
                        mActivePointerId = INVALID_POINTER;
                        break;
                    }
                    final float x = MotionEventCompat.getX(ev, pointerIndex);
                    final float xDiff = Math.abs(x - mLastX);
                    final float y = MotionEventCompat.getY(ev, pointerIndex);
                    final float yDiff = Math.abs(y - mLastY);
                    if ((mIsFlippingVertically && yDiff > mTouchSlop && yDiff > xDiff)
                            || (!mIsFlippingVertically && xDiff > mTouchSlop && xDiff > yDiff)) {
                        setFlipping(true);
                        mLastX = x;
                        mLastY = y;
                    }
                }
                if (isFlipping()) {
                    // Scroll to follow the motion event
                    final int activePointerIndex = MotionEventCompat
                            .findPointerIndex(ev, mActivePointerId);
                    if (activePointerIndex == -1) {
                        mActivePointerId = INVALID_POINTER;
                        break;
                    }
                    final float x = MotionEventCompat.getX(ev, activePointerIndex);
                    final float deltaX = mLastX - x;
                    final float y = MotionEventCompat.getY(ev, activePointerIndex);
                    final float deltaY = mLastY - y;
                    mLastX = x;
                    mLastY = y;

                    float deltaFlipDistance = 0;
                    if (mIsFlippingVertically) {
                        deltaFlipDistance = deltaY;
                    } else {
                        deltaFlipDistance = deltaX;
                    }

                    deltaFlipDistance /= ((isFlippingVertically() ? getHeight()
                            : getWidth()) / FLIP_DISTANCE_PER_PAGE);
                    setFlipDistance(mFlipDistance + deltaFlipDistance);

                    final int minFlipDistance = 0;
                    final int maxFlipDistance = (mPageCount - 1)
                            * FLIP_DISTANCE_PER_PAGE;
                    final boolean isOverFlipping = mFlipDistance < minFlipDistance
                            || mFlipDistance > maxFlipDistance;
                    if (isOverFlipping) {
                        mIsOverFlipping = true;
                        setFlipDistance(mOverFlipper.calculate(mFlipDistance,
                                minFlipDistance, maxFlipDistance));
                        if (mOnOverFlipListener != null) {
                            float overFlip = mOverFlipper.getTotalOverFlip();
                            mOnOverFlipListener.onOverFlip(this, mOverFlipMode,
                                    overFlip < 0, Math.abs(overFlip),
                                    FLIP_DISTANCE_PER_PAGE);
                        }
                    } else if (mIsOverFlipping) {
                        mIsOverFlipping = false;
                        if (mOnOverFlipListener != null) {
                            // TODO in the future should only notify flip distance 0
                            // on the correct edge (previous/next)
                            mOnOverFlipListener.onOverFlip(this, mOverFlipMode,
                                    false, 0, FLIP_DISTANCE_PER_PAGE);
                            mOnOverFlipListener.onOverFlip(this, mOverFlipMode,
                                    true, 0, FLIP_DISTANCE_PER_PAGE);
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isFlipping()) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);

                    int velocity = 0;
                    if (isFlippingVertically()) {
                        velocity = (int) VelocityTrackerCompat.getYVelocity(
                                velocityTracker, mActivePointerId);
                    } else {
                        velocity = (int) VelocityTrackerCompat.getXVelocity(
                                velocityTracker, mActivePointerId);
                    }
                    smoothFlipTo(getNextPage(velocity));

                    mActivePointerId = INVALID_POINTER;
                    endFlip();

                    mOverFlipper.overFlipEnded();
                }
                break;
            case MotionEventCompat.ACTION_POINTER_DOWN: {
                final int index = MotionEventCompat.getActionIndex(ev);
                final float x = MotionEventCompat.getX(ev, index);
                final float y = MotionEventCompat.getY(ev, index);
                mLastX = x;
                mLastY = y;
                mActivePointerId = MotionEventCompat.getPointerId(ev, index);
                break;
            }
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                final int index = MotionEventCompat.findPointerIndex(ev,
                        mActivePointerId);
                final float x = MotionEventCompat.getX(ev, index);
                final float y = MotionEventCompat.getY(ev, index);
                mLastX = x;
                mLastY = y;
                break;
        }
        if (mActivePointerId == INVALID_POINTER) {
            mLastTouchAllowed = false;
        }
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {

        if (mPageCount < 1) {
            return;
        }

        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            setFlipDistance(mScroller.getCurrY());
        }

        if (isFlipping() || !mScroller.isFinished() || mPeakAnim != null) {
            showAllPages();
            drawPreviousHalf(canvas);
            drawNextHalf(canvas);
            drawFlippingHalf(canvas);
        } else {
            endScroll();
            setDrawWithLayer(mCurrentPage.view, false);
            hideOtherPages(mCurrentPage);
            drawChild(canvas, mCurrentPage.view, 0);
            postFlippedToPage(mCurrentPageIndex);
        }

        // if overflip is GLOW mode and the edge effects needed drawing, make
        // sure to invalidate
        if (mOverFlipper.draw(canvas)) {
            // always invalidate whole screen as it is needed 99% of the time.
            // This is because of the shadows and shines put on the non-flipping
            // pages
            invalidate();
        }
    }

    private void hideOtherPages(Page p) {
        if (mPreviousPage != p && mPreviousPage.valid && mPreviousPage.view.getVisibility() != GONE) {
            mPreviousPage.view.setVisibility(GONE);
        }
        if (mCurrentPage != p && mCurrentPage.valid && mCurrentPage.view.getVisibility() != GONE) {
            mCurrentPage.view.setVisibility(GONE);
        }
        if (mNextPage != p && mNextPage.valid && mNextPage.view.getVisibility() != GONE) {
            mNextPage.view.setVisibility(GONE);
        }
        if (p.view != null)
            p.view.setVisibility(VISIBLE);
    }

    private void showAllPages() {
        if (mPreviousPage.valid && mPreviousPage.view.getVisibility() != VISIBLE) {
            mPreviousPage.view.setVisibility(VISIBLE);
        }
        if (mCurrentPage.valid && mCurrentPage.view.getVisibility() != VISIBLE) {
            mCurrentPage.view.setVisibility(VISIBLE);
        }
        if (mNextPage.valid && mNextPage.view.getVisibility() != VISIBLE) {
            mNextPage.view.setVisibility(VISIBLE);
        }
    }

    /**
     * draw top/left half
     *
     * @param canvas
     */
    private void drawPreviousHalf(Canvas canvas) {
        canvas.save();
        canvas.clipRect(isFlippingVertically() ? mTopRect : mLeftRect);

        final float degreesFlipped = getDegreesFlipped();
        final Page p = degreesFlipped > 90 ? mPreviousPage : mCurrentPage;

        // if the view does not exist, skip drawing it
        if (p.valid) {
            setDrawWithLayer(p.view, true);
            drawChild(canvas, p.view, 0);
        }

        drawPreviousShadow(canvas);
        canvas.restore();
    }

    /**
     * draw top/left half shadow
     *
     * @param canvas
     */
    private void drawPreviousShadow(Canvas canvas) {
        final float degreesFlipped = getDegreesFlipped();
        if (degreesFlipped > 90) {
            final int alpha = (int) (((degreesFlipped - 90) / 90f) * MAX_SHADOW_ALPHA);
            mShadowPaint.setAlpha(alpha);
            canvas.drawPaint(mShadowPaint);
        }
    }

    /**
     * draw bottom/right half
     *
     * @param canvas
     */
    private void drawNextHalf(Canvas canvas) {
        canvas.save();
        canvas.clipRect(isFlippingVertically() ? mBottomRect : mRightRect);

        final float degreesFlipped = getDegreesFlipped();
        final Page p = degreesFlipped > 90 ? mCurrentPage : mNextPage;

        // if the view does not exist, skip drawing it
        if (p.valid) {
            setDrawWithLayer(p.view, true);
            drawChild(canvas, p.view, 0);
        }

        drawNextShadow(canvas);
        canvas.restore();
    }

    /**
     * draw bottom/right half shadow
     *
     * @param canvas
     */
    private void drawNextShadow(Canvas canvas) {
        final float degreesFlipped = getDegreesFlipped();
        if (degreesFlipped < 90) {
            final int alpha = (int) ((Math.abs(degreesFlipped - 90) / 90f) * MAX_SHADOW_ALPHA);
            mShadowPaint.setAlpha(alpha);
            canvas.drawPaint(mShadowPaint);
        }
    }

    private void drawFlippingHalf(Canvas canvas) {
        canvas.save();
        mCamera.save();

        final float degreesFlipped = getDegreesFlipped();

        if (degreesFlipped > 90) {
            canvas.clipRect(isFlippingVertically() ? mTopRect : mLeftRect);
            if (mIsFlippingVertically) {
                mCamera.rotateX(degreesFlipped - 180);
            } else {
                mCamera.rotateY(180 - degreesFlipped);
            }
        } else {
            canvas.clipRect(isFlippingVertically() ? mBottomRect : mRightRect);
            if (mIsFlippingVertically) {
                mCamera.rotateX(degreesFlipped);
            } else {
                mCamera.rotateY(-degreesFlipped);
            }
        }

        mCamera.getMatrix(mMatrix);

        positionMatrix();
        canvas.concat(mMatrix);

        setDrawWithLayer(mCurrentPage.view, true);
        drawChild(canvas, mCurrentPage.view, 0);

        drawFlippingShadeShine(canvas);

        mCamera.restore();
        canvas.restore();
    }

    /**
     * will draw a shade if flipping on the previous(top/left) half and a shine
     * if flipping on the next(bottom/right) half
     *
     * @param canvas
     */
    private void drawFlippingShadeShine(Canvas canvas) {
        final float degreesFlipped = getDegreesFlipped();
        if (degreesFlipped < 90) {
            final int alpha = (int) ((degreesFlipped / 90f) * MAX_SHINE_ALPHA);
            mShinePaint.setAlpha(alpha);
            canvas.drawRect(isFlippingVertically() ? mBottomRect : mRightRect,
                    mShinePaint);
        } else {
            final int alpha = (int) ((Math.abs(degreesFlipped - 180) / 90f) * MAX_SHADE_ALPHA);
            mShadePaint.setAlpha(alpha);
            canvas.drawRect(isFlippingVertically() ? mTopRect : mLeftRect,
                    mShadePaint);
        }
    }

    /**
     * Enable a hardware layer for the view.
     *
     * @param v
     * @param drawWithLayer
     */
    private void setDrawWithLayer(View v, boolean drawWithLayer) {
        if (v != null && isHardwareAccelerated()) {
            if (v.getLayerType() != LAYER_TYPE_HARDWARE && drawWithLayer) {
                v.setLayerType(LAYER_TYPE_HARDWARE, null);
            } else if (v.getLayerType() != LAYER_TYPE_NONE && !drawWithLayer) {
                v.setLayerType(LAYER_TYPE_NONE, null);
            }
        }
    }

    private void positionMatrix() {
        mMatrix.preScale(0.25f, 0.25f);
        mMatrix.postScale(4.0f, 4.0f);
        mMatrix.preTranslate(-getWidth() / 2, -getHeight() / 2);
        mMatrix.postTranslate(getWidth() / 2, getHeight() / 2);
    }

    private float getDegreesFlipped() {
        float localFlipDistance = mFlipDistance % FLIP_DISTANCE_PER_PAGE;

        // fix for negative modulo. always want a positive flip degree
        if (localFlipDistance < 0) {
            localFlipDistance += FLIP_DISTANCE_PER_PAGE;
        }

        return (localFlipDistance / FLIP_DISTANCE_PER_PAGE) * 180;
    }

    private void postFlippedToPage(final int page) {
        if (getVisibility() != View.VISIBLE) {
            flipNotificationPending = true;
            return;
        }
        flipNotificationPending = false;
        if (mLastDispatchedPageEventIndex != page && mCurrentPage.valid) {
            boolean canNotify = mLastDispatchedPageEventIndex >= 0;
            mLastDispatchedPageEventIndex = page;
            try {
                mAdapter.setPrimaryItem(this, page, mCurrentPage.item);
            }
            catch (Exception e) {
                e.printStackTrace();
                return;
            }
            if (canNotify) {
                post(() -> {
                    if (mOnFlipListener != null) {
                        mOnFlipListener.onFlippedToPage(FlipView.this, page);
                    }
                });
            }
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        this.storedVisibility = visibility;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastX = MotionEventCompat.getX(ev, newPointerIndex);
            mActivePointerId = MotionEventCompat.getPointerId(ev,
                    newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    /**
     * @param deltaFlipDistance The distance to flip.
     * @return The duration for a flip, bigger deltaFlipDistance = longer
     * duration. The increase if duration gets smaller for bigger values
     * of deltaFlipDistance.
     */
    private int getFlipDuration(int deltaFlipDistance) {
        float distance = Math.abs(deltaFlipDistance);
        return (int) (MAX_SINGLE_PAGE_FLIP_ANIM_DURATION * Math.sqrt(distance
                / FLIP_DISTANCE_PER_PAGE));
    }

    /**
     * @param velocity
     * @return the page you should "land" on
     */
    private int getNextPage(int velocity) {
        int nextPage;
        if (velocity > mMinimumVelocity) {
            nextPage = getCurrentPageFloor();
        } else if (velocity < -mMinimumVelocity) {
            nextPage = getCurrentPageCeil();
        } else {
            nextPage = getCurrentPageRound();
        }
        return Math.min(Math.max(nextPage, 0), mPageCount - 1);
    }

    private int getCurrentPageRound() {
        return Math.round(mFlipDistance / FLIP_DISTANCE_PER_PAGE);
    }

    private int getCurrentPageFloor() {
        return (int) Math.floor(mFlipDistance / FLIP_DISTANCE_PER_PAGE);
    }

    private int getCurrentPageCeil() {
        return (int) Math.ceil(mFlipDistance / FLIP_DISTANCE_PER_PAGE);
    }

    /**
     * @return true if ended a flip
     */
    private boolean endFlip() {
        final boolean wasflipping = isFlipping();
        setFlipping(false);
        mIsUnableToFlip = false;
        mLastTouchAllowed = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        return wasflipping;
    }

    /**
     * @return true if ended a scroll
     */
    private boolean endScroll() {
        final boolean wasScrolling = !mScroller.isFinished();
        mScroller.abortAnimation();
        return wasScrolling;
    }

    /**
     * @return true if ended a peak
     */
    private boolean endPeak() {
        final boolean wasPeaking = mPeakAnim != null;
        if (mPeakAnim != null) {
            mPeakAnim.cancel();
            mPeakAnim = null;
        }
        return wasPeaking;
    }

    private void peak(boolean next, boolean once) {
        final float baseFlipDistance = mCurrentPageIndex
                * FLIP_DISTANCE_PER_PAGE;
        if (next) {
            mPeakAnim = ValueAnimator.ofFloat(baseFlipDistance,
                    baseFlipDistance + FLIP_DISTANCE_PER_PAGE / 4);
        } else {
            mPeakAnim = ValueAnimator.ofFloat(baseFlipDistance,
                    baseFlipDistance - FLIP_DISTANCE_PER_PAGE / 4);
        }
        mPeakAnim.setInterpolator(mPeakInterpolator);
        mPeakAnim.addUpdateListener(animation -> setFlipDistance((Float) animation.getAnimatedValue()));
        mPeakAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                endPeak();
            }
        });
        mPeakAnim.setDuration(PEAK_ANIM_DURATION);
        mPeakAnim.setRepeatMode(ValueAnimator.REVERSE);
        mPeakAnim.setRepeatCount(once ? 1 : ValueAnimator.INFINITE);
        mPeakAnim.start();
    }

    private void trackVelocity(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }

    private void updateEmptyStatus() {
        boolean empty = mAdapter == null || mPageCount == 0;

        if (empty) {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.VISIBLE);
                this.storedVisibility = getVisibility();
                super.setVisibility(View.GONE);
            } else {
                setVisibility(storedVisibility);
//                setVisibility(View.VISIBLE);
            }

        } else {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.GONE);
            }
            setVisibility(storedVisibility);
//            setVisibility(View.VISIBLE);
        }
    }

    /* ---------- API ---------- */

    /**
     * @param adapter a regular ListAdapter, not all methods if the list adapter are
     *                used by the flipview
     */
    public void setAdapter(PagerAdapter adapter) {
        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(dataSetObserver);
        }
        mCurrentPageIndex = 0;
        mLastDispatchedPageEventIndex = -1;

        // remove all the current views
        removeActiveViews();


        mAdapter = adapter;
        mPageCount = adapter == null ? 0 : mAdapter.getCount();

        if (adapter != null) {
            mAdapter.registerDataSetObserver(dataSetObserver);
        }

        // TODO pretty confusing
        // this will be correctly set in setFlipDistance method
        mCurrentPageIndex = PagerAdapter.POSITION_NONE;
        mFlipDistance = INVALID_FLIP_DISTANCE;
        setFlipDistance(0);

        updateEmptyStatus();
    }

    public PagerAdapter getAdapter() {
        return mAdapter;
    }

    public int getPageCount() {
        return mPageCount;
    }

    public int getCurrentPage() {
        return mCurrentPageIndex;
    }

    public void flipTo(int page) {
        if (page < 0 || page > mPageCount - 1) {
            throw new IllegalArgumentException("That page does not exist");
        }
        endFlip();
        setFlipDistance(page * FLIP_DISTANCE_PER_PAGE);
    }

    public void flipBy(int delta) {
        flipTo(mCurrentPageIndex + delta);
    }

    public void smoothFlipTo(int page) {
        if (page < 0 || page > mPageCount - 1) {
            throw new IllegalArgumentException("That page does not exist");
        }
        final int start = (int) mFlipDistance;
        final int delta = page * FLIP_DISTANCE_PER_PAGE - start;

        endFlip();
        mScroller.startScroll(0, start, 0, delta, getFlipDuration(delta));
        invalidate();
    }

    public void smoothFlipBy(int delta) {
        smoothFlipTo(mCurrentPageIndex + delta);
    }

    /**
     * Hint that there is a next page will do nothing if there is no next page
     *
     * @param once if true, only peak once. else peak until user interacts with
     *             view
     */
    public void peakNext(boolean once) {
        if (mCurrentPageIndex < mPageCount - 1) {
            peak(true, once);
        }
    }

    /**
     * Hint that there is a previous page will do nothing if there is no
     * previous page
     *
     * @param once if true, only peak once. else peak until user interacts with
     *             view
     */
    public void peakPrevious(boolean once) {
        if (mCurrentPageIndex > 0) {
            peak(false, once);
        }
    }

    /**
     * @return true if the view is flipping vertically, can only be set via xml
     * attribute "orientation"
     */
    public boolean isFlippingVertically() {
        return mIsFlippingVertically;
    }

    /**
     * The OnFlipListener will notify you when a page has been fully turned.
     *
     * @param onFlipListener
     */
    public void setOnFlipListener(OnFlipListener onFlipListener) {
        mOnFlipListener = onFlipListener;
    }

    public void setFlipScrollListener(OnFlipScrollListener flipScrollListener) {
        this.flipScrollListener = flipScrollListener;
    }

    /**
     * The OnOverFlipListener will notify of over flipping. This is a great
     * listener to have when implementing pull-to-refresh
     *
     * @param onOverFlipListener
     */
    public void setOnOverFlipListener(OnOverFlipListener onOverFlipListener) {
        this.mOnOverFlipListener = onOverFlipListener;
    }

    /**
     * @return the overflip mode of this flipview. Default is GLOW
     */
    public OverFlipMode getOverFlipMode() {
        return mOverFlipMode;
    }

    /**
     * Set the overflip mode of the flipview. GLOW is the standard seen in all
     * andriod lists. RUBBER_BAND is more like iOS lists which list you flip
     * past the first/last page but adding friction, like a rubber band.
     *
     * @param overFlipMode
     */
    public void setOverFlipMode(OverFlipMode overFlipMode) {
        this.mOverFlipMode = overFlipMode;
        mOverFlipper = OverFlipperFactory.create(this, mOverFlipMode);
    }

    /**
     * @param emptyView The view to show when either no adapter is set or the adapter
     *                  has no items. This should be a view already in the view
     *                  hierarchy which the FlipView will set the visibility of.
     */
    public void setEmptyView(View emptyView) {
        mEmptyView = emptyView;
        updateEmptyStatus();
    }

}
