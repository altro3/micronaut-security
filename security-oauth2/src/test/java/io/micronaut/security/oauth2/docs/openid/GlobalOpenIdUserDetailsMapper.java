package io.micronaut.security.oauth2.docs.openid;

//tag::clazz[]
import io.micronaut.context.annotation.Replaces;
import io.micronaut.security.authentication.UserDetails;
import io.micronaut.security.oauth2.endpoint.token.response.DefaultOpenIdUserDetailsMapper;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdClaims;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdTokenResponse;
import io.micronaut.security.oauth2.endpoint.token.response.OpenIdUserDetailsMapper;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;
import java.util.Collections;

@Singleton
@Replaces(DefaultOpenIdUserDetailsMapper.class)
public class GlobalOpenIdUserDetailsMapper implements OpenIdUserDetailsMapper {

    @Override
    @NonNull
    public UserDetails createAuthenticationResponse(String providerName, OpenIdTokenResponse tokenResponse, OpenIdClaims openIdClaims) {
        return new UserDetails("name", Collections.emptyList());
    }
}
//end::clazz[]