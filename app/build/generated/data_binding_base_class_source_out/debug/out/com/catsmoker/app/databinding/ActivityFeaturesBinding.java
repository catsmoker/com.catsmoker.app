// Generated by view binder compiler. Do not edit!
package com.catsmoker.app.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.viewbinding.ViewBinding;
import androidx.viewbinding.ViewBindings;
import com.catsmoker.app.R;
import com.google.android.material.button.MaterialButton;
import java.lang.NullPointerException;
import java.lang.Override;
import java.lang.String;

public final class ActivityFeaturesBinding implements ViewBinding {
  @NonNull
  private final ConstraintLayout rootView;

  @NonNull
  public final MaterialButton btnToggleCrosshair;

  @NonNull
  public final TextView comingSoonTitle;

  @NonNull
  public final TextView featuresTitle;

  @NonNull
  public final ImageView scope1;

  @NonNull
  public final ImageView scope2;

  @NonNull
  public final ImageView scope3;

  @NonNull
  public final ImageView scope4;

  @NonNull
  public final LinearLayout scopeImageContainer;

  @NonNull
  public final HorizontalScrollView scopeScrollView;

  private ActivityFeaturesBinding(@NonNull ConstraintLayout rootView,
      @NonNull MaterialButton btnToggleCrosshair, @NonNull TextView comingSoonTitle,
      @NonNull TextView featuresTitle, @NonNull ImageView scope1, @NonNull ImageView scope2,
      @NonNull ImageView scope3, @NonNull ImageView scope4,
      @NonNull LinearLayout scopeImageContainer, @NonNull HorizontalScrollView scopeScrollView) {
    this.rootView = rootView;
    this.btnToggleCrosshair = btnToggleCrosshair;
    this.comingSoonTitle = comingSoonTitle;
    this.featuresTitle = featuresTitle;
    this.scope1 = scope1;
    this.scope2 = scope2;
    this.scope3 = scope3;
    this.scope4 = scope4;
    this.scopeImageContainer = scopeImageContainer;
    this.scopeScrollView = scopeScrollView;
  }

  @Override
  @NonNull
  public ConstraintLayout getRoot() {
    return rootView;
  }

  @NonNull
  public static ActivityFeaturesBinding inflate(@NonNull LayoutInflater inflater) {
    return inflate(inflater, null, false);
  }

  @NonNull
  public static ActivityFeaturesBinding inflate(@NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent, boolean attachToParent) {
    View root = inflater.inflate(R.layout.activity_features, parent, false);
    if (attachToParent) {
      parent.addView(root);
    }
    return bind(root);
  }

  @NonNull
  public static ActivityFeaturesBinding bind(@NonNull View rootView) {
    // The body of this method is generated in a way you would not otherwise write.
    // This is done to optimize the compiled bytecode for size and performance.
    int id;
    missingId: {
      id = R.id.btn_toggle_crosshair;
      MaterialButton btnToggleCrosshair = ViewBindings.findChildViewById(rootView, id);
      if (btnToggleCrosshair == null) {
        break missingId;
      }

      id = R.id.coming_soon_title;
      TextView comingSoonTitle = ViewBindings.findChildViewById(rootView, id);
      if (comingSoonTitle == null) {
        break missingId;
      }

      id = R.id.features_title;
      TextView featuresTitle = ViewBindings.findChildViewById(rootView, id);
      if (featuresTitle == null) {
        break missingId;
      }

      id = R.id.scope1;
      ImageView scope1 = ViewBindings.findChildViewById(rootView, id);
      if (scope1 == null) {
        break missingId;
      }

      id = R.id.scope2;
      ImageView scope2 = ViewBindings.findChildViewById(rootView, id);
      if (scope2 == null) {
        break missingId;
      }

      id = R.id.scope3;
      ImageView scope3 = ViewBindings.findChildViewById(rootView, id);
      if (scope3 == null) {
        break missingId;
      }

      id = R.id.scope4;
      ImageView scope4 = ViewBindings.findChildViewById(rootView, id);
      if (scope4 == null) {
        break missingId;
      }

      id = R.id.scope_image_container;
      LinearLayout scopeImageContainer = ViewBindings.findChildViewById(rootView, id);
      if (scopeImageContainer == null) {
        break missingId;
      }

      id = R.id.scope_scroll_view;
      HorizontalScrollView scopeScrollView = ViewBindings.findChildViewById(rootView, id);
      if (scopeScrollView == null) {
        break missingId;
      }

      return new ActivityFeaturesBinding((ConstraintLayout) rootView, btnToggleCrosshair,
          comingSoonTitle, featuresTitle, scope1, scope2, scope3, scope4, scopeImageContainer,
          scopeScrollView);
    }
    String missingId = rootView.getResources().getResourceName(id);
    throw new NullPointerException("Missing required view with ID: ".concat(missingId));
  }
}
