package org.torproject.snowflake.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import org.torproject.snowflake.R;
import org.torproject.snowflake.constants.ForegroundServiceConstants;
import org.torproject.snowflake.interfaces.MainFragmentCallback;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link MainFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class MainFragment extends Fragment {
    private static final String TAG = "MainFragment";
    MainFragmentCallback callback;

    public MainFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment.
     *
     * @return A new instance of fragment main_fragment.
     */

    public static MainFragment newInstance() {
        MainFragment fragment = new MainFragment();
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_main_fragment, container, false);
        Button startButton = rootView.findViewById(R.id.start_button);
        startButton.setOnClickListener(v -> {
            if (callback.isServiceRunning()) //Toggling the service.
                callback.serviceToggle(ForegroundServiceConstants.ACTION_STOP);
            else
                callback.serviceToggle(ForegroundServiceConstants.ACTION_START);
        });

        //Calling this in case we return back to this fragment from a different fragment.
        showServed();

        // Inflate the layout for this fragment
        return rootView;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        callback = (MainFragmentCallback) context;
    }

    public void showServed() {
        Log.d(TAG, "showServed: " + callback.getServed());
    }
}