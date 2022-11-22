package co.rsk.peg;

import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.Utils;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptOpCodes;
import co.rsk.config.BridgeConstants;
import co.rsk.config.BridgeMainNetConstants;
import co.rsk.config.BridgeRegTestConstants;
import co.rsk.config.BridgeTestNetConstants;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ActivationConfigsForTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.mock;

class P2shErpFederationTest {

    @Test
    void getRedeemScript_testnet() {
        test_getRedeemScript(BridgeTestNetConstants.getInstance());
    }

    @Test
    void getRedeemScript_mainnet() {
        test_getRedeemScript(BridgeMainNetConstants.getInstance());
    }

    @Test
    void getStandardRedeemscript() {
        List<FederationMember> members = FederationMember.getFederationMembersFromKeys(
                Arrays.asList(new BtcECKey(), new BtcECKey(), new BtcECKey())
        );
        Instant creationTime = Instant.now();
        int creationBlock = 0;
        NetworkParameters btcParams = BridgeRegTestConstants.getInstance().getBtcParams();

        ActivationConfig.ForBlock activations = ActivationConfigsForTest.all().forBlock(0);

        // Create a legacy powpeg and then a p2sh valid one. Both of them should produce the same standard redeem script

        Federation legacyFed = new Federation(
                members,
                creationTime,
                creationBlock,
                btcParams
        );

        P2shErpFederation p2shFed = new P2shErpFederation(
                members,
                creationTime,
                creationBlock,
                btcParams,
                Arrays.asList(new BtcECKey(), new BtcECKey()),
                10_000,
                activations
        );

        Assertions.assertEquals(legacyFed.getRedeemScript(), p2shFed.getStandardRedeemScript());
        Assertions.assertNotEquals(p2shFed.getRedeemScript(), p2shFed.getStandardRedeemScript());
    }

    private void test_getRedeemScript(BridgeConstants bridgeConstants) {
        List<BtcECKey> defaultKeys = bridgeConstants.getGenesisFederation().getBtcPublicKeys();
        List<BtcECKey> emergencyKeys = bridgeConstants.getErpFedPubKeysList();
        long activationDelay = bridgeConstants.getErpFedActivationDelay();

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);

        Federation p2shErpFederation = new P2shErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(defaultKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            bridgeConstants.getBtcParams(),
            emergencyKeys,
            activationDelay,
            activations
        );

        validateP2shErpRedeemScript(
            p2shErpFederation.getRedeemScript(),
            defaultKeys,
            emergencyKeys,
            activationDelay
        );
    }

    private void validateP2shErpRedeemScript(
        Script erpRedeemScript,
        List<BtcECKey> defaultMultisigKeys,
        List<BtcECKey> emergencyMultisigKeys,
        Long csvValue) {

        // Keys are sorted when added to the redeem script, so we need them sorted in order to validate
        defaultMultisigKeys.sort(BtcECKey.PUBKEY_COMPARATOR);
        emergencyMultisigKeys.sort(BtcECKey.PUBKEY_COMPARATOR);

        byte[] serializedCsvValue = Utils.signedLongToByteArrayLE(csvValue);

        byte[] script = erpRedeemScript.getProgram();
        Assertions.assertTrue(script.length > 0);

        int index = 0;

        // First byte should equal OP_NOTIF
        Assertions.assertEquals(ScriptOpCodes.OP_NOTIF, script[index++]);

        // Next byte should equal M, from an M/N multisig
        int m = defaultMultisigKeys.size() / 2 + 1;
        Assertions.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(m)), script[index++]);

        // Assert public keys
        for (BtcECKey key: defaultMultisigKeys) {
            byte[] pubkey = key.getPubKey();
            Assertions.assertEquals(pubkey.length, script[index++]);
            for (byte b : pubkey) {
                Assertions.assertEquals(b, script[index++]);
            }
        }

        // Next byte should equal N, from an M/N multisig
        int n = defaultMultisigKeys.size();
        Assertions.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(n)), script[index++]);

        // Next byte should equal OP_CHECKMULTISIG
        Assertions.assertEquals(Integer.valueOf(ScriptOpCodes.OP_CHECKMULTISIG).byteValue(), script[index++]);

        // Next byte should equal OP_ELSE
        Assertions.assertEquals(ScriptOpCodes.OP_ELSE, script[index++]);

        // Next byte should equal csv value length
        Assertions.assertEquals(serializedCsvValue.length, script[index++]);

        // Next bytes should equal the csv value in bytes
        for (int i = 0; i < serializedCsvValue.length; i++) {
            Assertions.assertEquals(serializedCsvValue[i], script[index++]);
        }

        Assertions.assertEquals(Integer.valueOf(ScriptOpCodes.OP_CHECKSEQUENCEVERIFY).byteValue(), script[index++]);
        Assertions.assertEquals(ScriptOpCodes.OP_DROP, script[index++]);

        // Next byte should equal M, from an M/N multisig
        m = emergencyMultisigKeys.size() / 2 + 1;
        Assertions.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(m)), script[index++]);

        for (BtcECKey key: emergencyMultisigKeys) {
            byte[] pubkey = key.getPubKey();
            Assertions.assertEquals(Integer.valueOf(pubkey.length).byteValue(), script[index++]);
            for (byte b : pubkey) {
                Assertions.assertEquals(b, script[index++]);
            }
        }

        // Next byte should equal N, from an M/N multisig
        n = emergencyMultisigKeys.size();
        Assertions.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(n)), script[index++]);

        // Next byte should equal OP_CHECKMULTISIG
        Assertions.assertEquals(Integer.valueOf(ScriptOpCodes.OP_CHECKMULTISIG).byteValue(), script[index++]);

        Assertions.assertEquals(ScriptOpCodes.OP_ENDIF, script[index++]);
    }
}
