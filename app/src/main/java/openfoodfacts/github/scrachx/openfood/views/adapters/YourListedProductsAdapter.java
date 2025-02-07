package openfoodfacts.github.scrachx.openfood.views.adapters;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import java.util.List;

import openfoodfacts.github.scrachx.openfood.R;
import openfoodfacts.github.scrachx.openfood.models.YourListedProduct;
import openfoodfacts.github.scrachx.openfood.network.OpenFoodAPIClient;
import openfoodfacts.github.scrachx.openfood.utils.CustomTextView;

public class YourListedProductsAdapter extends RecyclerView.Adapter<YourListedProductsAdapter.YourListProductsViewHolder> {
    private final boolean isLowBatteryMode;
    private final Context mContext;
    private final List<YourListedProduct> products;

    public YourListedProductsAdapter(Context context, List<YourListedProduct> products, boolean isLowBatteryMode) {
        this.mContext = context;
        this.products = products;
        this.isLowBatteryMode = isLowBatteryMode;
    }

    @NonNull
    @Override
    public YourListProductsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(mContext)
            .inflate(R.layout.your_listed_products_item, parent, false);
        return new YourListProductsViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull YourListProductsViewHolder holder, int position) {
        holder.imgProgressBar.setVisibility(View.VISIBLE);

        String productName = products.get(position).getProductName();
        String barcode = products.get(position).getBarcode();
        holder.tvTitle.setText(productName);
        holder.tvDetails.setText(products.get(position).getProductDetails());
        holder.tvBarcode.setText(barcode);

        if (!isLowBatteryMode) {
            Picasso.get()
                .load(products.get(position).getImageUrl())
                .placeholder(R.drawable.placeholder_thumb)
                .error(R.drawable.ic_no_red_24dp)
                .fit()
                .centerCrop()
                .into(holder.imgProduct, new Callback() {
                    @Override
                    public void onSuccess() {
                        holder.imgProgressBar.setVisibility(View.GONE);
                    }

                    @Override
                    public void onError(Exception ex) {
                        holder.imgProgressBar.setVisibility(View.GONE);
                    }
                });
        } else {
            holder.imgProduct.setBackground(mContext.getResources().getDrawable(R.drawable.placeholder_thumb));
            holder.imgProgressBar.setVisibility(View.INVISIBLE);
        }

        holder.itemView.setOnClickListener(v -> {
            ConnectivityManager cm = (ConnectivityManager) v.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
            if (isConnected) {
                OpenFoodAPIClient api = new OpenFoodAPIClient(v.getContext());
                api.openProduct(barcode, (Activity) v.getContext());
            }
        });
    }

    public void remove(YourListedProduct data) {
        int position = products.indexOf(data);
        products.remove(position);
        notifyItemRemoved(position);
    }

    @Override
    public int getItemCount() {
        return products.size();
    }

    public static class YourListProductsViewHolder extends RecyclerView.ViewHolder {
        final AppCompatImageView imgProduct;
        final ProgressBar imgProgressBar;
        final CustomTextView tvBarcode;
        final TextView tvDetails;
        final TextView tvTitle;

        public YourListProductsViewHolder(View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.titleYourListedProduct);
            tvDetails = itemView.findViewById(R.id.productDetailsYourListedProduct);
            tvBarcode = itemView.findViewById(R.id.barcodeYourListedProduct);
            imgProduct = itemView.findViewById(R.id.imgProductYourListedProduct);
            imgProgressBar = itemView.findViewById(R.id.imageProgressbarYourListedProduct);
        }
    }
}

