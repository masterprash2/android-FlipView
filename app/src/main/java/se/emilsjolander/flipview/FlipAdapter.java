package se.emilsjolander.flipview;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import com.flipview.segment.R;

import java.util.ArrayList;
import java.util.List;

public class FlipAdapter extends PagerAdapter implements OnClickListener {

    public interface Callback {
        public void onPageRequested(int page);
    }

    static class Item {
        static long id = 0;

        long mId;

        public Item() {
            mId = id++;
        }

        long getId() {
            return mId;
        }
    }

    private LayoutInflater inflater;
    private Callback callback;
    private List<Item> items = new ArrayList<Item>();

    public FlipAdapter(Context context) {
        inflater = LayoutInflater.from(context);
        for (int i = 0; i < 10; i++) {
            items.add(new Item());
        }
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        ViewHolder holder = new ViewHolder(inflater.inflate(R.layout.page, container, false));
        View convertView = holder.view;

        holder.text = (TextView) convertView.findViewById(R.id.text);
        holder.firstPage = (Button) convertView.findViewById(R.id.first_page);
        holder.lastPage = (Button) convertView.findViewById(R.id.last_page);

        holder.firstPage.setOnClickListener(this);
        holder.lastPage.setOnClickListener(this);
        holder.text.setText(items.get(position).getId() + ":" + position);
        convertView.setTag(holder);
        container.addView(convertView);
        return holder;
    }

    static class ViewHolder {
        private final View view;
        TextView text;
        Button firstPage;
        Button lastPage;

        public ViewHolder(View view) {
            this.view = view;
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.first_page:
                if (callback != null) {
                    callback.onPageRequested(0);
                }
                break;
            case R.id.last_page:
                if (callback != null) {
                    callback.onPageRequested(getCount() - 1);
                }
                break;
        }
    }

    public void addItems(int amount) {
        for (int i = 0; i < amount; i++) {
            items.add(Math.min(2,items.size()),new Item());
        }
        notifyDataSetChanged();
    }

    public void addItemsBefore(int amount) {
        for (int i = 0; i < amount; i++) {
            items.add(0, new Item());
        }
        notifyDataSetChanged();
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        container.removeView(((ViewHolder) object).view);
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return false;
    }
}
