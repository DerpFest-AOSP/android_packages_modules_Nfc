//
// Copyright (C) 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

#include <gtest/gtest.h>

#include <vector>

#include "nfa_dm_int.h"

static bool sCaptureSetConfig;
static std::vector<uint8_t> sCapturedSetConfig;

static tNFC_STATUS NfaDmTestSetConfig(uint8_t tlv_size, uint8_t* p_tlv_list) {
  if (sCaptureSetConfig) {
    sCapturedSetConfig.assign(p_tlv_list, p_tlv_list + tlv_size);
    return NFC_STATUS_OK;
  }
  return NFC_SetConfig(tlv_size, p_tlv_list);
}

#define NFC_SetConfig NfaDmTestSetConfig
#include "nfa_dm_main.cc"
#undef NFC_SetConfig

// tests for nfa_dm_is_protocol_supported

TEST(NfaDmMainTest, SupportedProtocolsWithoutSelRes) {
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_T1T, 0));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_T3T, 0));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_ISO_DEP, 0));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_NFC_DEP, 0));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_T5T, 0));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_MIFARE, 0));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFA_PROTOCOL_CI, 0));
}

TEST(NfaDmMainTest, ProtocolT2TWithMatchingSelRes) {
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_T2T, NFC_SEL_RES_NFC_FORUM_T2T));
}

TEST(NfaDmMainTest, ProtocolT2TWithNonMatchingSelRes) {
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_T2T, 0x00));
    EXPECT_FALSE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_T2T, 0xFF));
}

TEST(NfaDmMainTest, UnsupportedProtocols) {
    EXPECT_TRUE(nfa_dm_is_protocol_supported(0xFF, 0));
}

TEST(NfaDmMainTest, EdgeCases) {
    EXPECT_FALSE(nfa_dm_is_protocol_supported(0, 0));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(0xFF, 0xFF));
    EXPECT_TRUE(nfa_dm_is_protocol_supported(NFC_PROTOCOL_ISO_DEP, NFC_SEL_RES_NFC_FORUM_T2T));
}

// tests for nfa_dm_evt_2_str

TEST(NfaDmMainTest, ValidEvents) {
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(NFA_DM_API_ENABLE_EVT)), "NFA_DM_API_ENABLE_EVT");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(NFA_DM_API_DISABLE_EVT)), "NFA_DM_API_DISABLE_EVT");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(
            NFA_DM_API_SET_CONFIG_EVT)), "NFA_DM_API_SET_CONFIG_EVT");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(
            NFA_DM_API_GET_CONFIG_EVT)), "NFA_DM_API_GET_CONFIG_EVT");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(
            NFA_DM_API_START_RF_DISCOVERY_EVT)), "NFA_DM_API_START_RF_DISCOVERY_EVT");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(
            NFA_DM_API_STOP_RF_DISCOVERY_EVT)), "NFA_DM_API_STOP_RF_DISCOVERY_EVT");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(
            NFA_DM_API_RELEASE_EXCL_RF_CTRL_EVT)), "NFA_DM_API_RELEASE_EXCL_RF_CTRL_EVT");
}

TEST(NfaDmMainTest, UnknownEvents) {
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(0x1234)), "Unknown or Vendor Specific");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(0xFFFF)), "Unknown or Vendor Specific");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(0x00FF)), "Unknown or Vendor Specific");
}

TEST(NfaDmMainTest, BoundaryEventCodes) {
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(0x0000)), "NFA_DM_API_ENABLE_EVT");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(0xFFFF)), "Unknown or Vendor Specific");
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(NFA_DM_API_ENABLE_EVT)), "NFA_DM_API_ENABLE_EVT");
}

TEST(NfaDmMainTest, EventsNotMapped) {
    EXPECT_EQ(std::string(nfa_dm_evt_2_str(0x00A0)), "Unknown or Vendor Specific");
}

//tests for nfa_dm_check_set_config

TEST(NfaDmMainTest, ValidSingleTypeTLV) {
    uint8_t tlv_list[] = {NFC_PMID_PF_RC, 1, 0x01};
    uint8_t tlv_list_len = sizeof(tlv_list);
    EXPECT_EQ(nfa_dm_check_set_config(tlv_list_len, tlv_list, false), NFA_STATUS_OK);
}

TEST(NfaDmMainTest, InvalidTLVLength) {
    uint8_t tlv_list[] = {NFC_PMID_PF_RC, 5, 0x01};
    uint8_t tlv_list_len = sizeof(tlv_list) - 1;
    EXPECT_EQ(nfa_dm_check_set_config(tlv_list_len, tlv_list, false), NFA_STATUS_FAILED);
}

TEST(NfaDmMainTest, ExceedMaxPendingSetConfigs) {
    nfa_dm_cb.setcfg_pending_num = NFA_DM_SETCONFIG_PENDING_MAX;
    uint8_t tlv_list[] = {NFC_PMID_PF_RC, 1, 0x01};
    EXPECT_EQ(nfa_dm_check_set_config(sizeof(tlv_list), tlv_list, false), NFA_STATUS_FAILED);
}

TEST(NfaDmMainTest, UpdateRequired) {
    memset(nfa_dm_cb.params.pf_rc, 0x00, NCI_PARAM_LEN_PF_RC);
    uint8_t tlv_list[] = {NFC_PMID_PF_RC, 1, 0x01};
    auto result = nfa_dm_check_set_config(sizeof(tlv_list), tlv_list, false);
    LOG(VERBOSE) << "Function returned: " << result;
    LOG(VERBOSE) << "Updated pf_rc[0]: " << (int)nfa_dm_cb.params.pf_rc[1];
    EXPECT_EQ(result, 0x03);
    EXPECT_EQ(nfa_dm_cb.params.pf_rc[0], 0x00);
}

TEST(NfaDmMainTest, NoUpdateNeeded) {
    nfa_dm_cb.params.pf_rc[0] = 0x01;
    uint8_t tlv_list[] = {NFC_PMID_PF_RC, 1, 0x01};
    EXPECT_EQ(nfa_dm_check_set_config(sizeof(tlv_list), tlv_list, false), 0x03);
    ASSERT_EQ(nfa_dm_cb.params.pf_rc[0], 0x01);
}

TEST(NfaDmMainTest, AppInitiatedUnchangedConfigSendsCompleteTlv) {
    const uint8_t previousPending = nfa_dm_cb.setcfg_pending_num;
    nfa_dm_cb.setcfg_pending_num = 0;
    nfa_dm_cb.params.la_bit_frame_sdd_len = 1;
    nfa_dm_cb.params.la_bit_frame_sdd[0] = 0x04;
    uint8_t tlv_list[] = {NFC_PMID_LA_BIT_FRAME_SDD, 1, 0x04};

    sCapturedSetConfig.clear();
    sCaptureSetConfig = true;
    const tNFA_STATUS result = nfa_dm_check_set_config(sizeof(tlv_list), tlv_list, true);
    const uint8_t pendingAfter = nfa_dm_cb.setcfg_pending_num;
    nfa_dm_cb.setcfg_pending_num = previousPending;
    sCaptureSetConfig = false;

    EXPECT_EQ(result, NFA_STATUS_OK);
    ASSERT_EQ(sCapturedSetConfig.size(), sizeof(tlv_list));
    EXPECT_EQ(sCapturedSetConfig[0], NFC_PMID_LA_BIT_FRAME_SDD);
    EXPECT_EQ(sCapturedSetConfig[1], 1);
    EXPECT_EQ(sCapturedSetConfig[2], 0x04);
    EXPECT_EQ(pendingAfter, 1);
}

TEST(NfaDmMainTest, InvalidType) {
    uint8_t tlv_list[] = {0xFF, 1, 0x01};
    EXPECT_EQ(nfa_dm_check_set_config(sizeof(tlv_list), tlv_list, false), 0x03);
}

TEST(NfaDmMainTest, MultipleTLVs) {
    uint8_t tlv_list[] = {
        NFC_PMID_PF_RC, 1, 0x01,
        NFC_PMID_TOTAL_DURATION, 1, 0x05
    };
    uint8_t result = nfa_dm_check_set_config(sizeof(tlv_list), tlv_list, false);
    EXPECT_EQ(result, 0x03);
    EXPECT_EQ(nfa_dm_cb.params.pf_rc[0], 0x01);
}

TEST(NfaDmMainTest, EmptyTLVList) {
    uint8_t* tlv_list = nullptr;
    EXPECT_EQ(nfa_dm_check_set_config(0, tlv_list, false), 0x03);
}

TEST(NfaDmMainTest, ExcessiveTLVLength) {
    uint8_t tlv_list[255];
    memset(tlv_list, 0, sizeof(tlv_list));
    tlv_list[0] = NFC_PMID_PF_RC;
    tlv_list[1] = 254;
    EXPECT_EQ(nfa_dm_check_set_config(sizeof(tlv_list), tlv_list, false), NFA_STATUS_FAILED);
}
