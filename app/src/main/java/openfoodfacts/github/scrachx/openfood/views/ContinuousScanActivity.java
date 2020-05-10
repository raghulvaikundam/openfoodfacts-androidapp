package openfoodfacts.github.scrachx.openfood.views;

import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.BeepManager;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.camera.CameraSettings;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;

import org.apache.commons.lang.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import openfoodfacts.github.scrachx.openfood.BuildConfig;
import openfoodfacts.github.scrachx.openfood.R;
import openfoodfacts.github.scrachx.openfood.databinding.ActivityContinuousScanBinding;
import openfoodfacts.github.scrachx.openfood.models.AllergenHelper;
import openfoodfacts.github.scrachx.openfood.models.AllergenName;
import openfoodfacts.github.scrachx.openfood.models.AnalysisTagConfig;
import openfoodfacts.github.scrachx.openfood.models.HistoryProductDao;
import openfoodfacts.github.scrachx.openfood.models.InvalidBarcode;
import openfoodfacts.github.scrachx.openfood.models.InvalidBarcodeDao;
import openfoodfacts.github.scrachx.openfood.models.OfflineSavedProduct;
import openfoodfacts.github.scrachx.openfood.models.OfflineSavedProductDao;
import openfoodfacts.github.scrachx.openfood.models.Product;
import openfoodfacts.github.scrachx.openfood.models.State;
import openfoodfacts.github.scrachx.openfood.models.eventbus.ProductNeedsRefreshEvent;
import openfoodfacts.github.scrachx.openfood.network.OpenFoodAPIClient;
import openfoodfacts.github.scrachx.openfood.utils.LocaleHelper;
import openfoodfacts.github.scrachx.openfood.utils.OfflineProductService;
import openfoodfacts.github.scrachx.openfood.utils.ProductUtils;
import openfoodfacts.github.scrachx.openfood.utils.Utils;
import openfoodfacts.github.scrachx.openfood.views.listeners.BottomNavigationListenerInstaller;
import openfoodfacts.github.scrachx.openfood.views.product.ProductFragment;
import openfoodfacts.github.scrachx.openfood.views.product.ingredients_analysis.IngredientsWithTagDialogFragment;
import openfoodfacts.github.scrachx.openfood.views.product.summary.IngredientAnalysisTagsAdapter;
import openfoodfacts.github.scrachx.openfood.views.product.summary.SummaryProductPresenter;
import openfoodfacts.github.scrachx.openfood.views.product.summary.SummaryProductPresenterView;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

public class ContinuousScanActivity extends AppCompatActivity {
    private static final int ADD_PRODUCT_ACTIVITY_REQUEST_CODE = 1;
    private static final int LOGIN_ACTIVITY_REQUEST_CODE = 2;
    private BeepManager beepManager;
    private ActivityContinuousScanBinding binding;
    private BottomSheetBehavior bottomSheetBehavior;
    private int cameraState;
    private OpenFoodAPIClient client;
    private Disposable disposable;
    private Handler handler;
    private boolean isAnalysisTagsEmpty = true;
    private String lastText;
    private boolean mAutofocus;
    private boolean mFlash;
    private HistoryProductDao mHistoryProductDao;
    private InvalidBarcodeDao mInvalidBarcodeDao;
    private OfflineSavedProductDao mOfflineSavedProductDao;
    private boolean mRing;
    private int peekLarge;
    private int peekSmall;
    private PopupMenu popup;
    private Product product;
    private ProductFragment productFragment;
    private boolean productShowing = false;
    private Runnable runnable;
    private SharedPreferences sp;
    private SummaryProductPresenter summaryProductPresenter;
    private BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            handler.removeCallbacks(runnable);
            if (result.getText() == null || result.getText().isEmpty() || result.getText().equals(lastText)) {
                // Prevent duplicate scans
                return;
            }
            InvalidBarcode invalidBarcode = mInvalidBarcodeDao.queryBuilder()
                .where(InvalidBarcodeDao.Properties.Barcode.eq(result.getText())).unique();
            if (invalidBarcode != null) {
                // scanned barcode is in the list of invalid barcodes, do nothing
                return;
            }

            if (mRing) {
                beepManager.playBeepSound();
            }

            lastText = result.getText();
            if (!(isFinishing())) {
                findProduct(lastText);
            }
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
            // Here possible results are useless but we must implement this
        }
    };

    /**
     * Used by screenshot tests.
     *
     * @param barcode barcode to serach
     */
    @SuppressWarnings("unused")
    public void showProduct(String barcode) {
        productShowing = true;
        binding.barcodeScanner.setVisibility(GONE);
        binding.barcodeScanner.pause();
        binding.imageForScreenshotGenerationOnly.setVisibility(VISIBLE);
        findProduct(barcode);
    }

    /**
     * Makes network call and search for the product in the database
     *
     * @param lastBarcode Barcode to be searched
     */
    private void findProduct(String lastBarcode) {
        if (isFinishing()) {
            return;
        }
        if (disposable != null && !disposable.isDisposed()) {
            //dispose the previous call if not ended.
            disposable.dispose();
        }
        if (summaryProductPresenter != null) {
            summaryProductPresenter.dispose();
        }

        OfflineSavedProduct offlineSavedProduct = OfflineProductService.getOfflineProductByBarcode(lastBarcode);
        if (offlineSavedProduct != null) {
            showOfflineSavedDetails(offlineSavedProduct);
        }

        client.getProductFullSingle(lastBarcode, Utils.HEADER_USER_AGENT_SCAN)
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe(a -> {
                hideAllViews();
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                binding.quickView.setOnClickListener(null);
                binding.quickViewProgress.setVisibility(VISIBLE);
                binding.quickViewProgressText.setVisibility(VISIBLE);
                binding.quickViewProgressText.setText(getString(R.string.loading_product, lastBarcode));
            })
            .subscribe(new SingleObserver<State>() {
                @Override
                public void onSubscribe(Disposable d) {
                    disposable = d;
                }

                @Override
                public void onSuccess(State state) {
                    //clear product tags
                    isAnalysisTagsEmpty = true;
                    binding.quickViewTags.setAdapter(null);

                    binding.quickViewProgress.setVisibility(GONE);
                    binding.quickViewProgressText.setVisibility(GONE);
                    if (state.getStatus() == 0) {
                        if (offlineSavedProduct != null) {
                            showOfflineSavedDetails(offlineSavedProduct);
                        } else {
                            productNotFound(getString(R.string.product_not_found, lastBarcode));
                        }
                    } else {
                        product = state.getProduct();
                        if (getIntent().getBooleanExtra("compare_product", false)) {
                            Intent intent = new Intent(ContinuousScanActivity.this, ProductComparisonActivity.class);
                            intent.putExtra("product_found", true);
                            ArrayList<Product> productsToCompare = (ArrayList<Product>) getIntent().getExtras().get("products_to_compare");
                            if (productsToCompare.contains(product)) {
                                intent.putExtra("product_already_exists", true);
                            } else {
                                productsToCompare.add(product);
                            }
                            intent.putExtra("products_to_compare", productsToCompare);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(intent);
                        }
                        new HistoryTask(mHistoryProductDao).execute(product);
                        showAllViews();
                        binding.txtProductCallToAction.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                        binding.txtProductCallToAction.setBackground(ContextCompat.getDrawable(ContinuousScanActivity.this, R.drawable.rounded_quick_view_text));
                        binding.txtProductCallToAction.setText(isProductIncomplete() ? R.string.product_not_complete : R.string.scan_tooltip);
                        binding.txtProductCallToAction.setVisibility(VISIBLE);

                        manageSummary(product);
                        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
                        productShownInBottomView();
                        binding.quickViewProductNotFound.setVisibility(GONE);
                        binding.quickViewProductNotFoundButton.setVisibility(GONE);

                        if (offlineSavedProduct != null && !TextUtils.isEmpty(offlineSavedProduct.getName())) {
                            binding.quickViewName.setText(offlineSavedProduct.getName());
                        } else if (product.getProductName() == null || product.getProductName().equals("")) {
                            binding.quickViewName.setText(R.string.productNameNull);
                        } else {
                            binding.quickViewName.setText(product.getProductName());
                        }
                        List<String> addTags = product.getAdditivesTags();
                        if (!addTags.isEmpty()) {
                            binding.quickViewAdditives.setText(getResources().getQuantityString(R.plurals.productAdditives, addTags.size(), addTags.size()));
                        } else if (product.getStatesTags().contains("en:ingredients-completed")) {
                            binding.quickViewAdditives.setText(getString(R.string.productAdditivesNone));
                        } else {
                            binding.quickViewAdditives.setText(getString(R.string.productAdditivesUnknown));
                        }

                        final String imageUrl = Utils.firstNotEmpty(offlineSavedProduct != null ? offlineSavedProduct.getImageFrontLocalUrl() : null,
                            product.getImageUrl(LocaleHelper.getLanguage(getBaseContext())));
                        if (imageUrl != null) {
                            try {
                                Picasso.get()
                                    .load(imageUrl)
                                    .error(R.drawable.placeholder_thumb)
                                    .into(binding.quickViewImage, new Callback() {
                                        @Override
                                        public void onSuccess() {
                                            binding.quickViewImageProgress.setVisibility(GONE);
                                        }

                                        @Override
                                        public void onError(Exception ex) {
                                            binding.quickViewImageProgress.setVisibility(GONE);
                                        }
                                    });
                            } catch (IllegalStateException e) {
                                //could happen if Picasso is not instanciate correctly...
                                Log.w(this.getClass().getSimpleName(), e.getMessage(), e);
                            }
                        } else {
                            binding.quickViewImage.setImageResource(R.drawable.placeholder_thumb);
                            binding.quickViewImageProgress.setVisibility(GONE);
                        }
                        // Hide nutriScore from quickView if app flavour is not OFF or there is no nutriscore
                        if (BuildConfig.FLAVOR.equals("off") && product.getNutritionGradeFr() != null) {
                            if (Utils.getImageGrade(product.getNutritionGradeFr()) != Utils.NO_DRAWABLE_RESOURCE) {
                                binding.quickViewNutriScore.setVisibility(VISIBLE);
                                binding.quickViewNutriScore.setImageResource(Utils.getImageGrade(product.getNutritionGradeFr()));
                            } else {
                                binding.quickViewNutriScore.setVisibility(INVISIBLE);
                            }
                        } else {
                            binding.quickViewNutriScore.setVisibility(GONE);
                        }
                        // Hide nova group from quickView if app flavour is not OFF or there is no nova group
                        if (BuildConfig.FLAVOR.equals("off") && product.getNovaGroups() != null) {
                            final int novaGroupDrawable = Utils.getNovaGroupDrawable(product);
                            if (novaGroupDrawable != Utils.NO_DRAWABLE_RESOURCE) {
                                binding.quickViewNovaGroup.setVisibility(VISIBLE);
                                binding.quickViewAdditives.setVisibility(VISIBLE);
                                binding.quickViewNovaGroup.setImageResource(novaGroupDrawable);
                            } else {
                                binding.quickViewNovaGroup.setVisibility(INVISIBLE);
                            }
                        } else {
                            binding.quickViewNovaGroup.setVisibility(GONE);
                        }
                        int environmentImpactResource = Utils.getImageEnvironmentImpact(product);
                        if (environmentImpactResource != Utils.NO_DRAWABLE_RESOURCE) {
                            binding.quickViewCo2Icon.setVisibility(VISIBLE);
                            binding.quickViewCo2Icon.setImageResource(environmentImpactResource);
                        } else {
                            binding.quickViewCo2Icon.setVisibility(INVISIBLE);
                        }
                        FragmentManager fm = getSupportFragmentManager();
                        FragmentTransaction fragmentTransaction = fm.beginTransaction();
                        ProductFragment newProductFragment = new ProductFragment();

                        Bundle bundle = new Bundle();
                        bundle.putSerializable("state", state);

                        newProductFragment.setArguments(bundle);
                        fragmentTransaction.replace(R.id.frame_layout, newProductFragment);
                        fragmentTransaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
                        fragmentTransaction.commitAllowingStateLoss();
                        productFragment = newProductFragment;
                    }
                }

                @Override
                public void onError(Throwable e) {
                    try {
                        // A network error happened
                        if (e instanceof IOException) {
                            hideAllViews();
                            OfflineSavedProduct offlineSavedProduct = mOfflineSavedProductDao.queryBuilder().where(OfflineSavedProductDao.Properties.Barcode.eq(lastBarcode))
                                .unique();
                            if (offlineSavedProduct != null) {
                                showOfflineSavedDetails(offlineSavedProduct);
                            } else {
                                productNotFound(getString(R.string.addProductOffline, lastBarcode));
                            }
                            binding.quickView.setOnClickListener(v -> navigateToProductAddition(lastBarcode));
                        } else {
                            binding.quickViewProgress.setVisibility(GONE);
                            binding.quickViewProgressText.setVisibility(GONE);
                            final Toast errorMessage = Toast.makeText(ContinuousScanActivity.this.getBaseContext(), R.string.txtConnectionError, Toast.LENGTH_LONG);
                            errorMessage.setGravity(Gravity.CENTER, 0, 0);
                            errorMessage.show();
                            Log.i(this.getClass().getSimpleName(), e.getMessage(), e);
                        }
                    } catch (Exception e1) {
                        Log.i(this.getClass().getSimpleName(), e1.getMessage(), e1);
                    }
                }
            });
    }

    private void manageSummary(Product product) {
        binding.callToActionImageProgress.setVisibility(VISIBLE);
        summaryProductPresenter = new SummaryProductPresenter(product, new SummaryProductPresenterView() {
            @Override
            public void showAllergens(List<AllergenName> allergens) {
                final AllergenHelper.Data data = AllergenHelper.computeUserAllergen(product, allergens);
                binding.callToActionImageProgress.setVisibility(GONE);
                if (data.isEmpty()) {
                    return;
                }
                final IconicsDrawable iconicsDrawable = new IconicsDrawable(ContinuousScanActivity.this)
                    .icon(GoogleMaterial.Icon.gmd_warning)
                    .color(ContextCompat.getColor(ContinuousScanActivity.this, R.color.white))
                    .sizeDp(24);
                binding.txtProductCallToAction.setCompoundDrawablesWithIntrinsicBounds(iconicsDrawable, null, null, null);
                binding.txtProductCallToAction.setBackground(ContextCompat.getDrawable(ContinuousScanActivity.this, R.drawable.rounded_quick_view_text_warn));
                if (data.isIncomplete()) {
                    binding.txtProductCallToAction.setText(R.string.product_incomplete_message);
                } else {
                    String text = String.format("%s\n", getResources().getString(R.string.product_allergen_prompt)) +
                        StringUtils.join(data.getAllergens(), ", ");
                    binding.txtProductCallToAction.setText(text);
                }
            }

            @Override
            public void showAnalysisTags(List<AnalysisTagConfig> analysisTags) {
                super.showAnalysisTags(analysisTags);

                if (analysisTags.size() == 0) {
                    binding.quickViewTags.setVisibility(GONE);
                    isAnalysisTagsEmpty = true;
                    return;
                }

                binding.quickViewTags.setVisibility(VISIBLE);
                isAnalysisTagsEmpty = false;

                IngredientAnalysisTagsAdapter adapter = new IngredientAnalysisTagsAdapter(ContinuousScanActivity.this, analysisTags);
                adapter.setOnItemClickListener((view, position) -> {
                    IngredientsWithTagDialogFragment fragment = IngredientsWithTagDialogFragment
                        .newInstance(product, (AnalysisTagConfig) view.getTag(R.id.analysis_tag_config));
                    fragment.show(getSupportFragmentManager(), "fragment_ingredients_with_tag");

                    fragment.setOnDismissListener(dialog -> adapter.filterVisibleTags());
                });
                binding.quickViewTags.setAdapter(adapter);
            }
        });
        summaryProductPresenter.loadAllergens(() -> binding.callToActionImageProgress.setVisibility(GONE));
        summaryProductPresenter.loadAnalysisTags();
    }

    private void productNotFound(String text) {
        hideAllViews();
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        binding.quickView.setOnClickListener(v -> navigateToProductAddition(lastText));
        binding.quickViewProductNotFound.setText(text);
        binding.quickViewProductNotFound.setVisibility(VISIBLE);
        binding.quickViewProductNotFoundButton.setVisibility(VISIBLE);
        binding.quickViewProductNotFoundButton.setOnClickListener(v -> navigateToProductAddition(lastText));
    }

    private void productShownInBottomView() {
        bottomSheetBehavior.setPeekHeight(peekLarge);
        binding.quickView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        binding.quickView.requestLayout();
        binding.quickView.getRootView().requestLayout();
    }

    private void showOfflineSavedDetails(OfflineSavedProduct offlineSavedProduct) {
        showAllViews();
        String pName = offlineSavedProduct.getName();
        if (!TextUtils.isEmpty(pName)) {
            binding.quickViewName.setText(pName);
        } else {
            binding.quickViewName.setText(R.string.productNameNull);
        }

        String imageFront = offlineSavedProduct.getImageFrontLocalUrl();
        if (!TextUtils.isEmpty(imageFront)) {
            Picasso.get()
                .load(imageFront)
                .error(R.drawable.placeholder_thumb)
                .into(binding.quickViewImage, new Callback() {
                    @Override
                    public void onSuccess() {
                        binding.quickViewImageProgress.setVisibility(GONE);
                    }

                    @Override
                    public void onError(Exception ex) {
                        binding.quickViewImageProgress.setVisibility(GONE);
                    }
                });
        } else {
            binding.quickViewImage.setImageResource(R.drawable.placeholder_thumb);
            binding.quickViewImageProgress.setVisibility(GONE);
        }
    }

    private void navigateToProductAddition(String lastText) {
        Intent intent = new Intent(ContinuousScanActivity.this, AddProductActivity.class);
        State st = new State();
        Product pd = new Product();
        pd.setCode(lastText);
        st.setProduct(pd);
        intent.putExtra("state", st);
        startActivityForResult(intent, ADD_PRODUCT_ACTIVITY_REQUEST_CODE);
    }

    private void showAllViews() {
        binding.quickViewSlideUpIndicator.setVisibility(VISIBLE);
        binding.quickViewImage.setVisibility(VISIBLE);
        binding.quickViewName.setVisibility(VISIBLE);
        binding.frameLayout.setVisibility(VISIBLE);
        binding.quickViewAdditives.setVisibility(VISIBLE);
        binding.quickViewImageProgress.setVisibility(VISIBLE);
        if (!isAnalysisTagsEmpty) {
            binding.quickViewTags.setVisibility(VISIBLE);
        } else {
            binding.quickViewTags.setVisibility(GONE);
        }
    }

    private void hideAllViews() {
        binding.quickViewSearchByBarcode.setVisibility(GONE);
        binding.quickViewProgress.setVisibility(GONE);
        binding.quickViewProgressText.setVisibility(GONE);
        binding.quickViewSlideUpIndicator.setVisibility(GONE);
        binding.quickViewImage.setVisibility(GONE);
        binding.quickViewName.setVisibility(GONE);
        binding.frameLayout.setVisibility(GONE);
        binding.quickViewAdditives.setVisibility(GONE);
        binding.quickViewNutriScore.setVisibility(GONE);
        binding.quickViewNovaGroup.setVisibility(GONE);
        binding.quickViewCo2Icon.setVisibility(GONE);
        binding.quickViewProductNotFound.setVisibility(GONE);
        binding.quickViewProductNotFoundButton.setVisibility(GONE);
        binding.quickViewImageProgress.setVisibility(GONE);
        binding.txtProductCallToAction.setVisibility(GONE);
        binding.quickViewTags.setVisibility(GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        if (summaryProductPresenter != null) {
            summaryProductPresenter.dispose();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.barcodeScanner.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BottomNavigationListenerInstaller.selectNavigationItem(binding.bottomNavigation.bottomNavigation, R.id.scan_bottom_nav);
        if (bottomSheetBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
            binding.barcodeScanner.resume();
        }
    }

    @Subscribe
    public void onEventBusProductNeedsRefreshEvent(ProductNeedsRefreshEvent event) {
        if (event.getBarcode().equals(lastText)) {
            findProduct(lastText);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        //status bar will remain visible if user presses home and then reopens the activity
        // hence hiding status bar again
        hideSystemUI();
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        OFFApplication.getAppComponent().inject(this);
        client = new OpenFoodAPIClient(this);
        super.onCreate(savedInstanceState);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_continuous_scan);

        binding.toggleFlash.setOnClickListener(v -> toggleFlash());
        binding.buttonMore.setOnClickListener(v -> moreSettings());

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }

        peekLarge = getResources().getDimensionPixelSize(R.dimen.scan_summary_peek_large);
        peekSmall = getResources().getDimensionPixelSize(R.dimen.scan_summary_peek_small);

        binding.quickViewTags.setNestedScrollingEnabled(false);

        View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener
            (visibility -> {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    // The system bars are visible.
                    hideSystemUI();
                }
            });

        handler = new Handler();
        runnable = () -> {
            if (productShowing) {
                return;
            }
            hideAllViews();
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            binding.quickViewSearchByBarcode.setVisibility(VISIBLE);
            binding.quickViewSearchByBarcode.requestFocus();
        };
        handler.postDelayed(runnable, 15000);

        bottomSheetBehavior = BottomSheetBehavior.from(binding.quickView);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        bottomSheetBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            float previousSlideOffset = 0;

            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    lastText = null;
                    binding.txtProductCallToAction.setVisibility(GONE);
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    binding.barcodeScanner.resume();
                }
                if (binding.quickViewSearchByBarcode.getVisibility() == VISIBLE) {
                    bottomSheetBehavior.setPeekHeight(peekSmall);
                    bottomSheet.getLayoutParams().height = bottomSheetBehavior.getPeekHeight();
                    bottomSheet.requestLayout();
                } else {
                    bottomSheetBehavior.setPeekHeight(peekLarge);
                    bottomSheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                    bottomSheet.requestLayout();
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                float slideDelta = slideOffset - previousSlideOffset;
                if (binding.quickViewSearchByBarcode.getVisibility() != VISIBLE && binding.quickViewProgress.getVisibility() != VISIBLE) {
                    if (slideOffset > 0.01f || slideOffset < -0.01f) {
                        binding.txtProductCallToAction.setVisibility(GONE);
                    } else {
                        if (binding.quickViewProductNotFound.getVisibility() != VISIBLE) {
                            binding.txtProductCallToAction.setVisibility(VISIBLE);
                        }
                    }
                    if (slideOffset > 0.01f) {
                        binding.quickViewDetails.setVisibility(GONE);
                        binding.quickViewTags.setVisibility(GONE);
                        binding.barcodeScanner.pause();
                        if (slideDelta > 0 && productFragment != null) {
                            productFragment.bottomSheetWillGrow();
                            binding.bottomNavigation.bottomNavigation.setVisibility(GONE);
                        }
                    } else {
                        binding.barcodeScanner.resume();
                        binding.quickViewDetails.setVisibility(VISIBLE);
                        if (!isAnalysisTagsEmpty) {
                            binding.quickViewTags.setVisibility(VISIBLE);
                        } else {
                            binding.quickViewTags.setVisibility(GONE);
                        }
                        binding.bottomNavigation.bottomNavigation.setVisibility(VISIBLE);
                        if (binding.quickViewProductNotFound.getVisibility() != VISIBLE) {
                            binding.txtProductCallToAction.setVisibility(VISIBLE);
                        }
                    }
                }
                previousSlideOffset = slideOffset;
            }
        });

        mHistoryProductDao = Utils.getAppDaoSession(ContinuousScanActivity.this).getHistoryProductDao();
        mInvalidBarcodeDao = Utils.getAppDaoSession(ContinuousScanActivity.this).getInvalidBarcodeDao();
        mOfflineSavedProductDao = Utils.getAppDaoSession(ContinuousScanActivity.this).getOfflineSavedProductDao();

        sp = getSharedPreferences("camera", 0);
        mRing = sp.getBoolean("ring", false);
        mFlash = sp.getBoolean("flash", false);
        mAutofocus = sp.getBoolean("focus", true);
        cameraState = sp.getInt("cameraState", 0);

        popup = new PopupMenu(this, binding.buttonMore);
        popup.getMenuInflater().inflate(R.menu.popup_menu, popup.getMenu());

        Collection<BarcodeFormat> formats = Arrays.asList(BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
            BarcodeFormat.RSS_14, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
            BarcodeFormat.CODE_128, BarcodeFormat.ITF);
        binding.barcodeScanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(formats));
        binding.barcodeScanner.setStatusText(null);
        CameraSettings settings = binding.barcodeScanner.getBarcodeView().getCameraSettings();
        settings.setRequestedCameraId(cameraState);
        if (mFlash) {
            binding.barcodeScanner.setTorchOn();
            binding.toggleFlash.setImageResource(R.drawable.ic_flash_on_white_24dp);
        }
        if (mRing) {
            popup.getMenu().findItem(R.id.toggleBeep).setChecked(true);
        }
        if (mAutofocus) {
            settings.setAutoFocusEnabled(true);
            popup.getMenu().findItem(R.id.toggleAutofocus).setChecked(true);
        } else {
            settings.setAutoFocusEnabled(false);
        }
        binding.barcodeScanner.decodeContinuous(callback);
        beepManager = new BeepManager(this);

        binding.quickViewSearchByBarcode.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                Utils.hideKeyboard(this);
                hideSystemUI();
                if (binding.quickViewSearchByBarcode.getText().toString().isEmpty()) {
                    Toast.makeText(this, getString(R.string.txtBarcodeNotValid), Toast.LENGTH_SHORT).show();
                } else {
                    String barcodeText = binding.quickViewSearchByBarcode.getText().toString();
                    //for debug only:the barcode 1 is used for test:
                    if (barcodeText.length() <= 2 && !ProductUtils.DEBUG_BARCODE.equals(barcodeText)) {
                        Toast.makeText(this, getString(R.string.txtBarcodeNotValid), Toast.LENGTH_SHORT).show();
                    } else {
                        if (ProductUtils.isBarcodeValid(barcodeText)) {
                            lastText = barcodeText;
                            binding.quickViewSearchByBarcode.setVisibility(GONE);
                            findProduct(barcodeText);
                        } else {
                            binding.quickViewSearchByBarcode.requestFocus();
                            Toast.makeText(this, getString(R.string.txtBarcodeNotValid), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                return true;
            }
            return false;
        });

        BottomNavigationListenerInstaller.install(binding.bottomNavigation.bottomNavigation, this, this);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onCreate(newBase));
    }

    private boolean isProductIncomplete() {
        return product != null && (product.getImageFrontUrl() == null || product.getImageFrontUrl().equals("") ||
            product.getQuantity() == null || product.getQuantity().equals("") ||
            product.getProductName() == null || product.getProductName().equals("") ||
            product.getBrands() == null || product.getBrands().equals("") ||
            product.getIngredientsText() == null || product.getIngredientsText().equals(""));
    }

    void toggleCamera() {
        SharedPreferences.Editor editor = sp.edit();
        CameraSettings settings = binding.barcodeScanner.getBarcodeView().getCameraSettings();
        if (binding.barcodeScanner.getBarcodeView().isPreviewActive()) {
            binding.barcodeScanner.pause();
        }
        if (settings.getRequestedCameraId() == Camera.CameraInfo.CAMERA_FACING_BACK) {
            cameraState = Camera.CameraInfo.CAMERA_FACING_FRONT;
        } else {
            cameraState = Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        settings.setRequestedCameraId(cameraState);
        binding.barcodeScanner.getBarcodeView().setCameraSettings(settings);
        editor.putInt("cameraState", cameraState);
        editor.apply();
        binding.barcodeScanner.resume();
    }

    void toggleFlash() {
        SharedPreferences.Editor editor = sp.edit();
        if (mFlash) {
            binding.barcodeScanner.setTorchOff();
            mFlash = false;
            binding.toggleFlash.setImageResource(R.drawable.ic_flash_off_white_24dp);
            editor.putBoolean("flash", false);
        } else {
            binding.barcodeScanner.setTorchOn();
            mFlash = true;
            binding.toggleFlash.setImageResource(R.drawable.ic_flash_on_white_24dp);
            editor.putBoolean("flash", true);
        }
        editor.apply();
    }

    void moreSettings() {
        popup.setOnMenuItemClickListener(item -> {
            SharedPreferences.Editor editor;
            switch (item.getItemId()) {
                case R.id.toggleBeep:
                    editor = sp.edit();
                    if (mRing) {
                        mRing = false;
                        item.setChecked(false);
                        editor.putBoolean("ring", false);
                    } else {
                        mRing = true;
                        item.setChecked(true);
                        editor.putBoolean("ring", true);
                    }
                    editor.apply();
                    break;
                case R.id.toggleAutofocus:
                    if (binding.barcodeScanner.getBarcodeView().isPreviewActive()) {
                        binding.barcodeScanner.pause();
                    }
                    editor = sp.edit();
                    CameraSettings settings = binding.barcodeScanner.getBarcodeView().getCameraSettings();
                    if (mAutofocus) {
                        mAutofocus = false;
                        settings.setAutoFocusEnabled(false);
                        item.setChecked(false);
                        editor.putBoolean("focus", false);
                    } else {
                        mAutofocus = true;
                        settings.setAutoFocusEnabled(true);
                        item.setChecked(true);
                        editor.putBoolean("focus", true);
                    }
                    binding.barcodeScanner.getBarcodeView().setCameraSettings(settings);
                    binding.barcodeScanner.resume();
                    editor.apply();
                    break;
                case R.id.troubleScanning:
                    hideAllViews();
                    handler.removeCallbacks(runnable);
                    binding.quickView.setOnClickListener(null);
                    binding.quickViewSearchByBarcode.setText(null);
                    binding.quickViewSearchByBarcode.setVisibility(VISIBLE);
                    binding.quickView.setVisibility(INVISIBLE);
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                    handler.postDelayed(() -> binding.quickView.setVisibility(VISIBLE), 500);
                    binding.quickViewSearchByBarcode.requestFocus();
                    break;
                case R.id.toggleCamera:
                    toggleCamera();
                    break;
                default:
                    break;
            }
            return true;
        });
        popup.show();
    }

    /**
     * Overridden to collapse bottom view after a back action from edit form.
     *
     * @param savedInstanceState
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ADD_PRODUCT_ACTIVITY_REQUEST_CODE && resultCode == RESULT_OK) {
            OfflineSavedProduct offlineSavedProduct = mOfflineSavedProductDao.queryBuilder().where(OfflineSavedProductDao.Properties.Barcode.eq(lastText)).unique();
            if (offlineSavedProduct != null) {
                hideAllViews();
                showOfflineSavedDetails(offlineSavedProduct);
            }
        } else if (requestCode == LOGIN_ACTIVITY_REQUEST_CODE && resultCode == RESULT_OK) {
            Intent intent = new Intent(ContinuousScanActivity.this, AddProductActivity.class);
            intent.putExtra(AddProductActivity.KEY_EDIT_PRODUCT, product);
            startActivityForResult(intent, ADD_PRODUCT_ACTIVITY_REQUEST_CODE);
        }
    }

    public void collapseBottomSheet() {
        if (bottomSheetBehavior != null) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        }
    }

    public void showIngredientsTab(String action) {
        if (bottomSheetBehavior != null) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
        productFragment.goToIngredients(action);
    }

    private static class HistoryTask extends AsyncTask<Product, Void, Void> {
        private final HistoryProductDao mHistoryProductDao;

        private HistoryTask(HistoryProductDao mHistoryProductDao) {
            this.mHistoryProductDao = mHistoryProductDao;
        }

        @Override
        protected Void doInBackground(Product... products) {
            OpenFoodAPIClient.addToHistory(mHistoryProductDao, products[0]);
            return null;
        }
    }
}
