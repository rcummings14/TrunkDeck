/* Test double (NOT shipped): a "SoapySDR module" that needs a library that is not installed, like the SDRplay module
 * without the SDRplay API. Linked against a stub libsdrplay_api.so.3 that the test then deletes. */
extern int sdrplay_api_Open(void);
int trunkdeck_broken_module(void) { return sdrplay_api_Open(); }
