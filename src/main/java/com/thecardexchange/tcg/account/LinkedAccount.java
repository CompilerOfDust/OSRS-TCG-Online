package com.thecardexchange.tcg.account;

import lombok.Value;

/** The exchange account a plugin token belongs to, as the API reports it. */
@Value
public class LinkedAccount
{
	String email;
	String osrsName;
}
