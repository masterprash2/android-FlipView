package com.clumob.flipview.segment

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import se.emilsjolander.flipview.FlipViewAdapter

class PagerFlipViewAdapter(val pagerAdapter: PagerAdapter) : FlipViewAdapter() {

    override fun setPrimaryItem(container: ViewGroup, position: Int, item: Any) =
        pagerAdapter.setPrimaryItem(container, position, item)


    override fun instantiateItem(container: ViewGroup, position: Int): Any =
        pagerAdapter.instantiateItem(container, position)


    override fun getItemPosition(item: Any): Int = pagerAdapter.getItemPosition(item)


    override fun destroyItem(container: ViewGroup, position: Int, item: Any) =
        pagerAdapter.destroyItem(container, position, item)


    override fun isViewFromObject(view: View, item: Any): Boolean =
        pagerAdapter.isViewFromObject(view, item)



    override fun getCount(): Int = pagerAdapter.count

}