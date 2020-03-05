package com.imobile3.groovypayments.data;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.imobile3.groovypayments.MainApplication;
import com.imobile3.groovypayments.data.entities.ProductEntity;
import com.imobile3.groovypayments.data.entities.ProductTaxJunctionEntity;
import com.imobile3.groovypayments.data.entities.TaxEntity;
import com.imobile3.groovypayments.data.model.Product;
import com.imobile3.groovypayments.logging.LogHelper;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class GroovyDemoManager {

    private static final String TAG = GroovyDemoManager.class.getSimpleName();

    //region Singleton Implementation

    private static GroovyDemoManager sInstance;

    private GroovyDemoManager() {
    }

    @NonNull
    public static synchronized GroovyDemoManager getInstance() {
        if (sInstance == null) {
            sInstance = new GroovyDemoManager();
        }
        return sInstance;
    }

    //endregion

    public interface ResetDatabaseCallback {

        void onDatabaseReset();
    }

    /**
     * Delete the current database instance (potentially dangerous operation!)
     * and populate a new instance with fresh demo data.
     */
    public void resetDatabase(
            @NonNull final ResetDatabaseCallback callback) {
        new ResetDatabaseTask(MainApplication.getInstance(), callback)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class ResetDatabaseTask extends AsyncTask<Void, Void, Void> {

        @NonNull
        private final Context mContext;
        @NonNull
        private final ResetDatabaseCallback mCallback;

        private ResetDatabaseTask(
                @NonNull final Context context,
                @NonNull final ResetDatabaseCallback callback) {
            mContext = context;
            mCallback = callback;
        }

        @Override
        protected Void doInBackground(Void... params) {
            // Blow away any existing database instance.
            DatabaseHelper.getInstance().eraseDatabase(mContext);

            // Initialize a new database instance.
            DatabaseHelper.getInstance().init(mContext);

            // Pretend that we have hundreds of thousands of records to create.
            // We need some sweet multi-threading action to speed things up!
            final ExecutorService executorService = Executors.newFixedThreadPool(4);

            // We only have one inventory worker right now.
            int howManyWorkers = 1;
            final CountDownLatch signal = new CountDownLatch(howManyWorkers);

            try {
                executorService.execute(new InventoryWorker(signal));
            } catch (RejectedExecutionException e) {
                // If a task is rejected or interrupted, we'll have some of our expected
                // demo records missing from the database (example: products with no taxes).
                LogHelper.write(TAG, Log.getStackTraceString(e));
            }

            // Block the current thread until all required tasks finish.
            try {
                // The await time is the absolute maximum time we will allow this process to take.
                signal.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                LogHelper.write(TAG, Log.getStackTraceString(e));
            }

            executorService.shutdown();

            // All done!
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            super.onPostExecute(result);
            mCallback.onDatabaseReset();
        }
    }

    /**
     * Generate the application inventory (products, taxes, etc).
     */
    private class InventoryWorker implements Runnable {

        @NonNull
        private final CountDownLatch mSignal;

        private InventoryWorker(@NonNull CountDownLatch signal) {
            mSignal = signal;
        }

        @Override
        public void run() {
            List<ProductEntity> productEntities = TestDataRepository.getInstance()
                    .getProducts(TestDataRepository.Environment.GroovyDemo);

            List<TaxEntity> taxEntities = TestDataRepository.getInstance()
                    .getTaxes(TestDataRepository.Environment.GroovyDemo);

            // Generate product-tax associations with junction entities.
            List<ProductTaxJunctionEntity> productTaxJunctionEntities = new ArrayList<>();
            for (ProductEntity product : productEntities) {
                // Associate all taxes with each product.
                productTaxJunctionEntities.addAll(TestDataRepository.getInstance()
                        .getProductTaxJunctions(product, taxEntities));
            }

            // Insert entities into database instance.
            DatabaseHelper.getInstance().getDatabase().getProductDao()
                    .insertProducts(
                            productEntities.toArray(new ProductEntity[0]));
            DatabaseHelper.getInstance().getDatabase().getTaxDao()
                    .insertTaxes(
                            taxEntities.toArray(new TaxEntity[0]));
            DatabaseHelper.getInstance().getDatabase().getProductTaxJunctionDao()
                    .insertProductTaxJunctions(
                            productTaxJunctionEntities.toArray(new ProductTaxJunctionEntity[0]));

            // Just for kicks, query all the data and verify the data exists.
            // Note: You probably wouldn't load all these records into memory - it could
            // potentially be megabytes of information.
            List<Product> productResults =
                    DatabaseHelper.getInstance().getDatabase()
                            .getProductDao().getProducts();
            List<TaxEntity> taxResults =
                    DatabaseHelper.getInstance().getDatabase()
                            .getTaxDao().getTaxes();
            List<ProductTaxJunctionEntity> productTaxJunctionsResults =
                    DatabaseHelper.getInstance().getDatabase()
                            .getProductTaxJunctionDao().getProductTaxJunctions();

            LogHelper.write(Level.CONFIG, TAG, "Successfully generated inventory data ..." +
                    "\n" + productResults.size() + " products" +
                    "\n" + taxResults.size() + " taxes" +
                    "\n" + productTaxJunctionsResults.size() + " product-tax-junctions");

            // The signal must always be notified once!
            mSignal.countDown();
        }
    }
}
