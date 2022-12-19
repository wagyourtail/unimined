package xyz.wagyourtail.unimined.fmlloader;

import net.minecraftforge.fml.loading.targets.ForgeServerUserdevLaunchHandler;

public class UniminedServerUserdev extends ForgeServerUserdevLaunchHandler {
    @Override
    public String getNaming() {
        return "unimined";
    }

    @Override
    public String name() {
        return "uniminedserveruserdev";
    }
}
