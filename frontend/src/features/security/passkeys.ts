import { identityAuthClient } from '../../services/api';

type DescriptorJson = Omit<PublicKeyCredentialDescriptor, 'id'> & { id: string };
type CreationJson = Omit<PublicKeyCredentialCreationOptions, 'challenge' | 'user' | 'excludeCredentials'> & {
  challenge: string;
  user: Omit<PublicKeyCredentialUserEntity, 'id'> & { id: string };
  excludeCredentials?: DescriptorJson[];
};
type RequestJson = Omit<PublicKeyCredentialRequestOptions, 'challenge' | 'allowCredentials'> & {
  challenge: string; allowCredentials?: DescriptorJson[];
};

export function decodeBase64Url(value: string): ArrayBuffer {
  const binary = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(binary, character => character.charCodeAt(0)).buffer;
}
export function encodeBase64Url(value: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(value))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
export function creationOptions(json: CreationJson): PublicKeyCredentialCreationOptions {
  return { ...json, challenge: decodeBase64Url(json.challenge), user: { ...json.user, id: decodeBase64Url(json.user.id) },
    excludeCredentials: json.excludeCredentials?.map(descriptor => ({ ...descriptor, id: decodeBase64Url(descriptor.id) })) };
}
export function assertionOptions(json: RequestJson): PublicKeyCredentialRequestOptions {
  return { ...json, challenge: decodeBase64Url(json.challenge), allowCredentials: json.allowCredentials?.map(descriptor => ({ ...descriptor, id: decodeBase64Url(descriptor.id) })) };
}
export function serializeCredential(credential: PublicKeyCredential) {
  const response = credential.response;
  const common = { id: credential.id, rawId: encodeBase64Url(credential.rawId), type: credential.type,
    clientExtensionResults: credential.getClientExtensionResults(), authenticatorAttachment: credential.authenticatorAttachment };
  if ('attestationObject' in response) {
    const registration = response as AuthenticatorAttestationResponse;
    return { ...common, response: { clientDataJSON: encodeBase64Url(response.clientDataJSON), attestationObject: encodeBase64Url(registration.attestationObject), transports: registration.getTransports?.() ?? [] } };
  }
  const assertion = response as AuthenticatorAssertionResponse;
  return { ...common, response: { clientDataJSON: encodeBase64Url(response.clientDataJSON), authenticatorData: encodeBase64Url(assertion.authenticatorData), signature: encodeBase64Url(assertion.signature), userHandle: assertion.userHandle ? encodeBase64Url(assertion.userHandle) : null } };
}

export async function registerPasskey(password: string) {
  const data = await identityAuthClient.passkeyRegistrationOptions({ password });
  const credential = await navigator.credentials.create({ publicKey: creationOptions(data.publicKey as CreationJson) }) as PublicKeyCredential | null;
  if (!credential) throw new Error('Passkey registration was cancelled.');
  await identityAuthClient.passkeyRegistrationVerify({ challengeId: data.challengeId, credential: serializeCredential(credential) });
}
export async function verifyPasskey() {
  const data = await identityAuthClient.passkeyAssertionOptions();
  const credential = await navigator.credentials.get({ publicKey: assertionOptions(data.publicKey as RequestJson) }) as PublicKeyCredential | null;
  if (!credential) throw new Error('Passkey verification was cancelled.');
  await identityAuthClient.passkeyAssertionVerify({ challengeId: data.challengeId, credential: serializeCredential(credential) });
}
