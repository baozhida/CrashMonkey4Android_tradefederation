/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tradefed.testtype;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.result.ITestInvocationListener;

/**
 * Mock for InstrumentationTest.
 */
public class MockInstrumentationTest extends InstrumentationTest {

    private ITestInvocationListener mListener = null;
    private DeviceNotAvailableException mException = null;

    @Override
    public void run(final ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        mListener = listener;
        if (mException != null) {
            throw mException;
        }
    }

    public ITestInvocationListener getListener() {
        return mListener;
    }

    public void clearListener() {
        mListener = null;
    }

    public void setException(DeviceNotAvailableException e) {
        mException = e;
    }
}
