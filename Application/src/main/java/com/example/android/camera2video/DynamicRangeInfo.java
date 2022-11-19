package com.example.android.camera2video;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class DynamicRangeInfo {
    String mName;
    Long mValue;

    public DynamicRangeInfo(String name, Long value) {
        this.mName = name;
        this.mValue = value;
    }

    public String getName() {
        return mName;
    }

    public void setName(String name) {
        this.mName = name;
    }

    public Long getValue() {
        return mValue;
    }

    public void setValue(Long value) {
        this.mValue = value;
    }

    private static String dynamicRangeProfileString(Long value) {
        if (value == DynamicRangeProfiles.STANDARD) {
            return "STANDARD";
        } else if (value == DynamicRangeProfiles.HLG10) {
            return "HLG10";
        } else if (value == DynamicRangeProfiles.HDR10) {
            return "HDR10";
        } else if (value == DynamicRangeProfiles.HDR10_PLUS) {
            return "HDR10_PLUS";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF) {
            return "DOLBY_VISION_10B_HDR_REF";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO) {
            return "DOLBY_VISION_10B_HDR_REF_PO";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM) {
            return "DOLBY_VISION_10B_HDR_OEM";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO) {
            return "DOLBY_VISION_10B_HDR_OEM_PO";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF) {
            return "DOLBY_VISION_8B_HDR_REF";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO) {
            return "DOLBY_VISION_8B_HDR_REF_PO";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM) {
            return "DOLBY_VISION_8B_HDR_OEM";
        } else if (value == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO) {
            return "DOLBY_VISION_8B_HDR_OEM_PO";
        }
        return "UNKNOWN";
    }

    public static boolean isTenBitProfileSupported(CameraCharacteristics characteristics) {
        int[] availableCapabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        for (int capability : availableCapabilities) {
            if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) {
                return true;
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static boolean isHLGSupport(CameraCharacteristics characteristics) {
        if (isTenBitProfileSupported(characteristics)) {
            DynamicRangeProfiles dynamicRangeProfiles = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
            return dynamicRangeProfiles.getSupportedProfiles().contains(DynamicRangeProfiles.HLG10);
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public static List<DynamicRangeInfo> enumerateDynamicRangeProfiles(CameraCharacteristics characteristics) {
        List<DynamicRangeInfo> dynamicRangeList = new ArrayList<>();
        DynamicRangeProfiles dynamicRangeProfiles = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
        for (Long profile : dynamicRangeProfiles.getSupportedProfiles()) {
            dynamicRangeList.add(new DynamicRangeInfo(dynamicRangeProfileString(profile), profile));
        }
        return dynamicRangeList;
    }

    @NonNull
    @Override
    public String toString() {
        return this.mName + ":" + mValue;
    }
}
