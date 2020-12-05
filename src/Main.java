public class Main {

    public enum ErrorCode {

        OK(0, "操作成功完成"),
        RESOURCE_NOT_FOUND(1, "资源未找到"),
        PERMISSION_DENIED(2, "权限不足"),
        ;

        private final int errorCode;
        private final String detailMessage;

        ErrorCode(int errorCode, String detailMessage) {
            this.errorCode = errorCode;
            this.detailMessage = detailMessage;
        }

        public static ErrorCode getInstanceById(int id) {
            for (ErrorCode errorCode : values()) {
                if (errorCode.errorCode == id) {
                    return errorCode;
                }
            }
            return null;
        }

        public int getErrorCode() {
            return errorCode;
        }

        public String getDetailMessage() {
            return detailMessage;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Thread a = new Thread() {
            @Override
            public void run() {
                super.run();
                System.out.println("a process");
            }
        };
        Thread b = new Thread() {
            @Override
            public void run() {
                super.run();
                try {
                    a.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println("wait return");
            }
        };
        a.start();
        b.start();
        a.join();
        System.out.println("a.join return");
    }
}
