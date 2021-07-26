package net.cellar;

import androidx.annotation.NonNull;
//TODO add a working url!
public class FrogPoxTest extends LoadTest {

    @NonNull
    @Override
    String getUrl() {
        return "https://www.dropbox.com/s/ptz4qc9r7zzblxp/4692.jpg?dl=0";
    }

    @Override
    public void execute() {
        load();
    }
}
